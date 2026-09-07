package com.familyledger.app.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Public release storage only; never reads credentials, account preferences, or ledger data. */
class AppUpdateService(context: Context) {
    private val context = context.applicationContext
    private val packageManager = this.context.packageManager
    private val updatesDirectory get() = File(context.cacheDir, "updates")
    val currentVersionCode: Long get() = versionCode(installedPackage())
    val currentVersionName: String get() = installedPackage().versionName ?: "未知"

    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        val connection = open(AppUpdate.MANIFEST_URL)
        try {
            requireResponse(connection, manifest = true)
            val declaredLength = connection.contentLengthLong
            require(declaredLength <= AppUpdateCodec.MAX_MANIFEST_BYTES) { "更新清单过大" }
            val deadline = System.nanoTime() + 30_000_000_000L
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    coroutine.ensureActive()
                    require(System.nanoTime() < deadline) { "检查更新超时，请稍后重试" }
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(bytes.size() + read <= AppUpdateCodec.MAX_MANIFEST_BYTES) { "更新清单过大" }
                    bytes.write(buffer, 0, read)
                }
            }
            coroutine.ensureActive()
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            val update = AppUpdateCodec.parse(text)
            if (!update.isNewerThan(currentVersionCode)) return@withContext null
            require(update.minSdk <= Build.VERSION.SDK_INT) { "新版本需要 Android API ${update.minSdk} 或更高版本" }
            update
        } finally { connection.disconnect() }
    }

    suspend fun download(update: AppUpdate, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        downloads.withLock {
            requireUpgrade(update)
            val coroutine = currentCoroutineContext()
            val directory = updatesDirectory
            check(directory.isDirectory || directory.mkdirs()) { "无法创建更新缓存目录" }
            // Only this service's cache files are removed; no user documents are touched.
            directory.listFiles()?.filter { it.name.endsWith(".part") || it.name.endsWith(".apk") }?.forEach { it.delete() }
            val destination = File(directory, "update-${update.versionCode}-${update.sha256.take(12)}.apk")
            val part = File(directory, "${UUID.randomUUID()}.part")
            val connection = open(update.downloadUrl())
            try {
                requireResponse(connection)
                val declaredLength = connection.contentLengthLong
                require(declaredLength == -1L || declaredLength == update.sizeBytes) { "安装包响应大小与清单不符" }
                val deadline = System.nanoTime() + 300_000_000_000L
                connection.inputStream.use { input ->
                    UpdateTransfer.copyVerified(input, part, destination, update, {
                        coroutine.ensureActive()
                        require(System.nanoTime() < deadline) { "下载超时，请重试" }
                    }, onProgress)
                }
                coroutine.ensureActive()
                verifyForInstall(update, destination)
                coroutine.ensureActive()
                destination
            } catch (error: Throwable) {
                part.delete(); destination.delete()
                throw error
            } finally { connection.disconnect() }
        }
    }

    /** Call on Dispatchers.IO again immediately before each installation attempt. */
    fun verifyForInstall(update: AppUpdate, file: File) {
        requireUpgrade(update)
        requireCacheFile(file)
        UpdateTransfer.verifyFile(file, update)
        val candidate = packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags())
            ?: throw IllegalArgumentException("下载文件不是可安装的 APK")
        require(candidate.packageName == AppUpdate.PACKAGE_NAME && candidate.packageName == context.packageName) { "安装包包名不匹配" }
        require(versionCode(candidate) == update.versionCode.toLong() && candidate.versionName == update.versionName) { "安装包版本与更新清单不符" }
        val candidateApplication = candidate.applicationInfo ?: throw IllegalArgumentException("安装包信息不完整")
        require(candidateApplication.minSdkVersion == update.minSdk && candidateApplication.minSdkVersion <= Build.VERSION.SDK_INT) { "安装包系统要求与清单不符或不兼容" }
        val installed = installedPackage()
        require(versionCode(candidate) > versionCode(installed)) { "不允许安装相同或更旧版本" }
        val installedSigners = currentSigners(installed)
        val candidateSigners = currentSigners(candidate)
        val history = if (Build.VERSION.SDK_INT >= 28) {
            candidate.signingInfo?.signingCertificateHistory?.map { it.toCharsString() }?.toSet().orEmpty()
        } else candidateSigners
        require(UpdateSignatures.compatible(installedSigners, candidateSigners, history)) { "安装包签名与当前应用不兼容，已拒绝安装" }
    }

    /** The caller owns unknown-source authorization and launches only after verifyForInstall. */
    fun installationIntent(file: File): Intent {
        requireCacheFile(file)
        require(file.isFile) { "安装包已被系统清理，请重新下载" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("应用更新", uri)
        }
    }

    private fun requireUpgrade(update: AppUpdate) {
        require(context.packageName == AppUpdate.PACKAGE_NAME && update.packageName == context.packageName) { "更新应用包名不匹配" }
        require(update.isNewerThan(currentVersionCode)) { "当前已是此版本或更新版本，请重新检查更新" }
        require(update.minSdk <= Build.VERSION.SDK_INT) { "新版本不支持当前 Android 系统" }
    }

    private fun requireCacheFile(file: File) {
        require(file.canonicalFile.parentFile == updatesDirectory.canonicalFile && file.name.endsWith(".apk")) { "安装包必须位于应用更新缓存目录" }
    }

    @Suppress("DEPRECATION")
    private fun installedPackage(): PackageInfo = packageManager.getPackageInfo(context.packageName, signatureFlags())

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun currentSigners(info: PackageInfo): Set<String> = if (Build.VERSION.SDK_INT >= 28) {
        info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
    } else info.signatures?.map { it.toCharsString() }?.toSet().orEmpty()

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 20_000
        useCaches = false
        setRequestProperty("Accept-Encoding", "identity")
        setRequestProperty("Cache-Control", "no-cache")
    }

    private fun requireResponse(connection: HttpURLConnection, manifest: Boolean = false) {
        val status = connection.responseCode
        if (manifest && status == 404) throw IllegalStateException("更新渠道尚未发布版本，请稍后重试")
        require(status == HttpURLConnection.HTTP_OK) {
            if (status in 300..399) "更新服务发生重定向，已停止下载" else "更新服务暂不可用（HTTP $status）"
        }
        require(connection.contentEncoding.isNullOrBlank() || connection.contentEncoding.equals("identity", ignoreCase = true)) { "更新服务返回不支持的内容编码" }
    }

    companion object { private val downloads = Mutex() }
}
