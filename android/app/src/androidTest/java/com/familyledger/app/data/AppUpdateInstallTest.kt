package com.familyledger.app.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

/** The CI-generated APKs are synthetic update fixtures and are never installed. */
class AppUpdateInstallTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val service = AppUpdateService(context)
    private fun fixture(name: String): File {
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(folder, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }
    private fun sha256(file: File) = UpdateTransfer.hex(java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
    @Suppress("DEPRECATION")
    private fun metadata(file: File): AppUpdate {
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)!!
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode.toInt() else archive.versionCode
        val name = archive.versionName!!
        return AppUpdate(1, code, name, 26, "com.familyledger.app",
            "releases/$code/AI家庭账本-v$name.apk", file.length(), sha256(file),
            "合成安装校验夹具", Instant.parse("2026-09-07T00:00:00Z"))
    }

    @Test fun sameSignerNewVersionVerifiesAndIntentGrantsOnlyReadAccess() {
        val file = fixture("update-valid.apk")
        try {
            service.verifyForInstall(metadata(file), file)
            val intent = service.installationIntent(file)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("content", intent.data!!.scheme)
            assertEquals("${context.packageName}.updates", intent.data!!.authority)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            context.contentResolver.openInputStream(intent.data!!).use { input -> assertNotNull(input); assertEquals(file.length(), input!!.readBytes().size.toLong()) }
        } finally { file.delete() }
    }

    @Test fun differentSignerIsRejectedEvenWithCorrectManifestHash() {
        val file = fixture("update-wrong-signer.apk")
        try {
            val error = assertThrows(IllegalArgumentException::class.java) { service.verifyForInstall(metadata(file), file) }
            assertTrue(error.message.orEmpty().contains("签名"))
        } finally { file.delete() }
    }

    @Test fun alteredFileIsRejectedBeforeInstallation() {
        val file = fixture("update-valid.apk")
        try {
            val update = metadata(file)
            java.io.RandomAccessFile(file, "rw").use {
                it.seek(100); val original = it.readByte().toInt()
                it.seek(100); it.writeByte(original xor 1)
            }
            val error = assertThrows(IllegalArgumentException::class.java) { service.verifyForInstall(update, file) }
            assertTrue(error.message.orEmpty().contains("校验"))
        } finally { file.delete() }
    }

    @Test fun arbitraryFileWithMatchingHashIsNotAnApk() {
        val file = fixture("update-valid.apk")
        try {
            val original = metadata(file)
            file.writeText("not an Android package")
            val update = original.copy(sizeBytes = file.length(), sha256 = sha256(file))
            val error = assertThrows(IllegalArgumentException::class.java) { service.verifyForInstall(update, file) }
            assertTrue(error.message.orEmpty().contains("APK"))
        } finally { file.delete() }
    }

    @Test fun versionMismatchIsRejectedEvenForCorrectSigner() {
        val file = fixture("update-valid.apk")
        try {
            val actual = metadata(file)
            val otherCode = actual.versionCode + 1
            val update = actual.copy(versionCode = otherCode, apkPath = "releases/$otherCode/AI家庭账本-v${actual.versionName}.apk")
            assertThrows(IllegalArgumentException::class.java) { service.verifyForInstall(update, file) }
            val oldVersion = metadata(file).copy(versionCode = 1, versionName = "0.1.0", apkPath = "releases/1/AI家庭账本-v0.1.0.apk")
            assertThrows(IllegalArgumentException::class.java) { service.verifyForInstall(oldVersion, file) }
        } finally { file.delete() }
    }

    @Test fun providerAndServiceRejectFilesOutsideUpdateDirectory() {
        val outside = File(context.cacheDir, "private-ledger-fixture.apk").apply { writeText("synthetic private data") }
        try {
            assertThrows(IllegalArgumentException::class.java) { service.installationIntent(outside) }
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, "${context.packageName}.updates", outside)
            }
        } finally { outside.delete() }
    }
}
