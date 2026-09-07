package com.familyledger.app.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CancellationException

class AppUpdateTest {
    private val manifest = """{"schemaVersion":1,"versionCode":12,"versionName":"0.12.0","minSdk":26,"packageName":"com.familyledger.app","apkPath":"releases/12/ai-family-ledger-v0.12.0.apk","sizeBytes":3,"sha256":"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad","notes":"修复问题\n保留账本","publishedAt":"2026-09-07T00:00:00Z"}"""

    @Test fun parsesReleaseAndUsesNumericCodeForUpgrade() {
        val update = AppUpdateCodec.parse(manifest)
        assertEquals(Instant.parse("2026-09-07T00:00:00Z"), update.publishedAt)
        assertTrue(update.isNewerThan(11))
        assertFalse(update.isNewerThan(12))
        assertFalse(update.isNewerThan(13))
        assertEquals("https://xdgeybztysuvvwagqkvb.supabase.co/storage/v1/object/public/app-updates/releases/12/ai-family-ledger-v0.12.0.apk", update.downloadUrl())
    }

    @Test fun rejectsUnicodeStorageKeyInsteadOfPublishingAnUndownloadableUpdate() {
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdateCodec.parse(manifest.replace("ai-family-ledger", "AI家庭账本"))
        }
        assertEquals("releases/12/ai-family-ledger-v0.12.0.apk", AppUpdateCodec.parse(manifest).apkPath)
    }

    @Test fun rejectsUntrustedPathsAndInvalidManifestFields() {
        val invalid = listOf(
            manifest.replace("releases/12/", "../12/"),
            manifest.replace("releases/12/", "https://evil.test/"),
            manifest.replace("releases/12/", "releases/13/"),
            manifest.replace("com.familyledger.app", "com.other.app"),
            manifest.replace("\"minSdk\":26", "\"minSdk\":25"),
            manifest.replace("\"sizeBytes\":3", "\"sizeBytes\":52428801"),
            manifest.replace("\"sizeBytes\":3", "\"sizeBytes\":0"),
            manifest.replace("\"versionCode\":12", "\"versionCode\":0"),
            manifest.replace("\"versionCode\":12", "\"versionCode\":2147483648"),
            manifest.replace("0.12.0", "v0.12.0"),
            manifest.replace("ba7816", "BA7816"),
            manifest.replace("2026-09-07T00:00:00Z", "2026-09-07"),
            manifest.replace("修复问题", "x".repeat(4001)),
            manifest.replace("修复问题", "\\u0000")
        )
        invalid.forEach { value -> assertThrows(IllegalArgumentException::class.java) { AppUpdateCodec.parse(value) } }
    }

    @Test fun rejectsJsonCoercionDuplicatesUnknownFieldsAndTrailingData() {
        val invalid = listOf(
            manifest.replace("\"versionCode\":12", "\"versionCode\":\"12\""),
            manifest.replace("\"versionCode\":12", "\"versionCode\":12.0"),
            manifest.replace("\"versionCode\":12", "\"versionCode\":012"),
            manifest.replace("\"versionCode\":12", "\"versionCode\":12,\"versionCode\":13"),
            manifest.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"apkUrl\":\"https://evil.test/a.apk\""),
            manifest.replace("\"schemaVersion\":1", "schemaVersion:1"),
            manifest.replace("\"schemaVersion\":1", "'schemaVersion':1"),
            manifest.dropLast(1) + ",}", manifest + "{}"
        )
        invalid.forEach { value -> assertThrows(IllegalArgumentException::class.java) { AppUpdateCodec.parse(value) } }
    }

    @Test fun transferPublishesOnlyCompleteVerifiedFile() {
        val folder = Files.createTempDirectory("update-test").toFile()
        try {
            val part = folder.resolve("download.part"); val apk = folder.resolve("ready.apk")
            val progress = mutableListOf<Long>()
            UpdateTransfer.copyVerified(ByteArrayInputStream("abc".toByteArray()), part, apk,
                AppUpdateCodec.parse(manifest), {}, { done, _ -> progress.add(done) })
            assertEquals("abc", apk.readText()); assertFalse(part.exists())
            assertEquals(3L, progress.last())
        } finally { folder.deleteRecursively() }
    }

    @Test fun failedSizeHashOrCancellationNeverPublishesApkAndRemovesPartialFile() {
        val update = AppUpdateCodec.parse(manifest)
        for (bytes in listOf("ab", "abcd", "abd")) {
            val folder = Files.createTempDirectory("update-test").toFile()
            try {
                val part = folder.resolve("download.part"); val apk = folder.resolve("ready.apk")
                assertThrows(IllegalArgumentException::class.java) {
                    UpdateTransfer.copyVerified(ByteArrayInputStream(bytes.toByteArray()), part, apk, update, {}, { _, _ -> })
                }
                assertFalse(part.exists()); assertFalse(apk.exists())
            } finally { folder.deleteRecursively() }
        }
        val folder = Files.createTempDirectory("update-cancel").toFile()
        try {
            val part = folder.resolve("download.part"); val apk = folder.resolve("ready.apk")
            assertThrows(CancellationException::class.java) {
                UpdateTransfer.copyVerified(ByteArrayInputStream("abc".toByteArray()), part, apk, update,
                    { throw CancellationException() }, { _, _ -> })
            }
            assertFalse(part.exists()); assertFalse(apk.exists())
        } finally { folder.deleteRecursively() }
    }

    @Test fun signerCompatibilityRequiresExactSetOrForwardRotation() {
        assertTrue(UpdateSignatures.compatible(setOf("a"), setOf("a"), setOf("a")))
        assertTrue(UpdateSignatures.compatible(setOf("a"), setOf("b"), setOf("a", "b")))
        assertFalse(UpdateSignatures.compatible(setOf("b"), setOf("a"), setOf("a")))
        assertFalse(UpdateSignatures.compatible(setOf("a"), setOf("x"), setOf("x")))
        assertTrue(UpdateSignatures.compatible(setOf("a", "b"), setOf("b", "a"), emptySet()))
        assertFalse(UpdateSignatures.compatible(setOf("a", "b"), setOf("a"), setOf("a", "b")))
        assertFalse(UpdateSignatures.compatible(emptySet(), emptySet(), emptySet()))
    }
}
