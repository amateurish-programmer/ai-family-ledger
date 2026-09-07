package com.familyledger.app.data

import org.json.JSONTokener
import java.io.File
import java.io.InputStream
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant

data class AppUpdate(
    val schemaVersion: Int,
    val versionCode: Int,
    val versionName: String,
    val minSdk: Int,
    val packageName: String,
    val apkPath: String,
    val sizeBytes: Long,
    val sha256: String,
    val notes: String,
    val publishedAt: Instant
) {
    init {
        require(schemaVersion == 1) { "不支持的更新清单版本" }
        require(versionCode > 0) { "更新版本号无效" }
        require(versionName.length <= 32 && versionName.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) { "更新版本名称无效" }
        require(minSdk >= 26) { "更新系统要求无效" }
        require(packageName == PACKAGE_NAME) { "更新包名不匹配" }
        require(apkPath == "releases/$versionCode/AI家庭账本-v$versionName.apk") { "更新安装包路径无效" }
        require(sizeBytes in 1..MAX_APK_BYTES) { "更新安装包大小无效（最大 50 MB）" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "更新校验值无效" }
        require(notes.length <= 4000 && notes.none { it.isISOControl() && it !in "\n\r\t" }) { "更新说明无效" }
    }

    fun isNewerThan(installedVersionCode: Long): Boolean = versionCode.toLong() > installedVersionCode
    fun downloadUrl(): String = PUBLIC_ROOT + apkPath.split('/').joinToString("/") {
        URLEncoder.encode(it, "UTF-8").replace("+", "%20")
    }

    companion object {
        const val PACKAGE_NAME = "com.familyledger.app"
        const val MAX_APK_BYTES = 50L * 1024 * 1024
        const val PUBLIC_ROOT = "https://xdgeybztysuvvwagqkvb.supabase.co/storage/v1/object/public/app-updates/"
        const val MANIFEST_URL = PUBLIC_ROOT + "latest.json"
    }
}

/** Flat strict JSON grammar avoids Android JSONObject's numeric coercion and lenient syntax. */
object AppUpdateCodec {
    const val MAX_MANIFEST_BYTES = 32 * 1024
    fun parse(json: String): AppUpdate = try {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) { "更新清单过大" }
        val fields = FlatJson(json).parse()
        require(fields.keys == setOf("schemaVersion", "versionCode", "versionName", "minSdk", "packageName", "apkPath", "sizeBytes", "sha256", "notes", "publishedAt")) { "更新清单字段不完整或包含未知字段" }
        fun string(key: String) = (fields[key] as? String) ?: throw IllegalArgumentException("更新清单 $key 类型无效")
        fun number(key: String) = (fields[key] as? Long) ?: throw IllegalArgumentException("更新清单 $key 类型无效")
        fun integer(key: String): Int {
            val value = number(key)
            require(value in 0..Int.MAX_VALUE.toLong()) { "更新清单 $key 超出范围" }
            return value.toInt()
        }
        AppUpdate(integer("schemaVersion"), integer("versionCode"), string("versionName"), integer("minSdk"),
            string("packageName"), string("apkPath"), number("sizeBytes"), string("sha256"), string("notes"), Instant.parse(string("publishedAt")))
    } catch (error: Exception) {
        throw IllegalArgumentException("更新清单无效：${error.message ?: "格式错误"}", error)
    }

    private class FlatJson(private val text: String) {
        private var cursor = 0
        private fun whitespace() { while (cursor < text.length && text[cursor] in " \r\n\t") cursor++ }
        private fun expect(char: Char) {
            whitespace(); require(cursor < text.length && text[cursor++] == char) { "JSON 格式错误" }
        }
        private fun string(): String {
            whitespace()
            val start = cursor
            expect('"')
            while (cursor < text.length) {
                val char = text[cursor++]
                require(char.code >= 32) { "JSON 字符串包含控制字符" }
                if (char == '"') return JSONTokener(text.substring(start, cursor)).nextValue() as String
                if (char == '\\') {
                    require(cursor < text.length) { "JSON 转义不完整" }
                    val escape = text[cursor++]
                    require(escape in "\"\\/bfnrtu") { "JSON 转义无效" }
                    if (escape == 'u') {
                        require(cursor + 4 <= text.length && text.substring(cursor, cursor + 4).all { it in "0123456789abcdefABCDEF" }) { "JSON Unicode 转义无效" }
                        cursor += 4
                    }
                }
            }
            throw IllegalArgumentException("JSON 字符串不完整")
        }
        fun parse(): Map<String, Any> {
            val result = linkedMapOf<String, Any>()
            expect('{')
            whitespace()
            if (cursor < text.length && text[cursor] != '}') {
                while (true) {
                    val key = string()
                    require(!result.containsKey(key)) { "JSON 字段重复" }
                    expect(':'); whitespace()
                    val value: Any = if (cursor < text.length && text[cursor] == '"') string() else {
                        val match = Regex("-?(0|[1-9][0-9]*)").matchAt(text, cursor)
                            ?: throw IllegalArgumentException("JSON 值类型无效")
                        cursor += match.value.length
                        match.value.toLong()
                    }
                    result[key] = value
                    whitespace()
                    if (cursor >= text.length || text[cursor] != ',') break
                    cursor++
                }
            }
            expect('}'); whitespace()
            require(cursor == text.length) { "JSON 包含尾随内容" }
            return result
        }
    }
}

internal object UpdateSignatures {
    fun compatible(installed: Set<String>, candidate: Set<String>, candidateHistory: Set<String>): Boolean {
        if (installed.isEmpty() || candidate.isEmpty()) return false
        if (installed == candidate) return true
        // Multiple signers cannot rotate. A single signer may only rotate forward.
        return installed.size == 1 && candidate.size == 1 && candidateHistory.containsAll(installed)
    }
}

internal object UpdateTransfer {
    fun copyVerified(input: InputStream, part: File, destination: File, update: AppUpdate,
        checkCancelled: () -> Unit, progress: (Long, Long) -> Unit) {
        var published = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            part.outputStream().use { output ->
                progress(0, update.sizeBytes)
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    checkCancelled()
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    require(count <= update.sizeBytes && count <= AppUpdate.MAX_APK_BYTES) { "安装包超过清单大小" }
                    output.write(buffer, 0, read); digest.update(buffer, 0, read)
                    progress(count, update.sizeBytes)
                }
                require(count == update.sizeBytes) { "安装包下载不完整" }
                require(hex(digest.digest()) == update.sha256) { "安装包校验失败，请重新检查更新" }
                output.fd.sync()
            }
            checkCancelled()
            Files.move(part.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            published = true
        } finally {
            part.delete()
            if (!published) destination.delete()
        }
    }

    fun verifyFile(file: File, update: AppUpdate) {
        require(file.isFile && file.length() == update.sizeBytes) { "安装包不存在或大小不符，请重新下载" }
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                require(count <= update.sizeBytes) { "安装包大小变化，请重新下载" }
                digest.update(buffer, 0, read)
            }
        }
        require(count == update.sizeBytes && hex(digest.digest()) == update.sha256) { "安装包校验失败，请重新下载" }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
