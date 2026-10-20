package com.familyledger.app.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

class NetworkDiagnosticFileProvider : FileProvider()

internal object NetworkDiagnosticFiles {
    suspend fun save(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw IOException("Document output is unavailable")
    }

    suspend fun shareIntent(context: Context, text: String): Intent = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "network-diagnostics")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Diagnostic cache is unavailable")
        val file = File.createTempFile("network-diagnostic-", ".txt", directory)
        try {
            file.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)) }
            coroutineContext.ensureActive()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("网络诊断文件", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }
}
