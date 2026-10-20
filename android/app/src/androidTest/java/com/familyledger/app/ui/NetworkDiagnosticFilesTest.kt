package com.familyledger.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NetworkDiagnosticFilesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val report = "{\"schema\":1,\"synthetic\":\"网络诊断🙂\"}\n"

    /** Catches encoding changes, truncated writes, or accidental use of private ledger storage. */
    @Test fun saveAndSharePreserveExactReportBytesWithoutOpeningLedgerStorage() = runBlocking {
        val guarded = object : ContextWrapper(context) {
            override fun getFilesDir(): File = error("Diagnostic export must not open ledger files")
            override fun getDatabasePath(name: String): File = error("Diagnostic export must not open ledger databases")
        }
        val target = File.createTempFile("synthetic-diagnostic-save-", ".txt", context.cacheDir)
        var share: Intent? = null
        try {
            NetworkDiagnosticFiles.save(guarded, Uri.fromFile(target), report)
            assertArrayEquals(report.toByteArray(Charsets.UTF_8), target.readBytes())
            share = NetworkDiagnosticFiles.shareIntent(guarded, report)
            assertEquals(Intent.ACTION_SEND, share.action)
            assertEquals("text/plain", share.type)
            assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, share.flags and (Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
            @Suppress("DEPRECATION")
            val uri = share.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("${context.packageName}.diagnostics", uri.authority)
            assertEquals(uri, share.clipData!!.getItemAt(0).uri)
            assertArrayEquals(report.toByteArray(Charsets.UTF_8), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertTrue(uri.lastPathSegment!!.endsWith(".txt"))
        } finally {
            target.delete()
            @Suppress("DEPRECATION")
            share?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { context.contentResolver.delete(it, null, null) }
        }
    }

    /** Catches broadening the provider to update APKs, ledger files, or sibling cache paths. */
    @Test fun providerOnlyAuthorizesTheDiagnosticCacheDirectory() {
        val authority = "${context.packageName}.diagnostics"
        val provider = context.packageManager.resolveContentProvider(authority, 0)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        val allowed = File(context.cacheDir, "network-diagnostics/synthetic.txt")
        assertEquals(authority, FileProvider.getUriForFile(context, authority, allowed).authority)
        listOf(File(context.filesDir, "synthetic.ledger.json"), File(context.cacheDir, "updates/synthetic.apk"),
            File(context.cacheDir, "network-diagnostics-other/synthetic.txt"), File(context.cacheDir, "synthetic.txt"))
            .forEach { denied ->
                try {
                    FileProvider.getUriForFile(context, authority, denied)
                    fail("Unexpected diagnostic access to ${denied.name}")
                } catch (_: IllegalArgumentException) { /* Expected: outside the shared root. */ }
            }
    }
}
