package com.familyledger.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.familyledger.app.data.NetworkDiagnosticReport
import com.familyledger.app.data.NetworkDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.coroutines.coroutineContext

/** Kept at the settings root so a document result survives secondary-page navigation. */
@Composable internal fun rememberNetworkDiagnosticExports(): NetworkDiagnosticExports {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingText by rememberSaveable { mutableStateOf<String?>(null) }
    var writing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = pendingText
        pendingText = null
        if (uri != null && text != null) {
            writing = true
            scope.launch {
                try {
                    NetworkDiagnosticFiles.save(context, uri, text)
                    message = "诊断文件已保存，可发给维护者"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    message = "无法保存诊断文件，请重试"
                } finally { writing = false }
            }
        }
    }
    val busy = writing || pendingText != null
    return NetworkDiagnosticExports(busy, message, onSave = { report ->
        if (!writing && pendingText == null) {
            message = null
            pendingText = report.text
            try { save.launch("家庭账本-网络诊断-${LocalDate.now()}.txt") }
            catch (_: Exception) { pendingText = null; message = "无法打开文件保存界面，请重试" }
        }
    }, onShare = { report ->
        if (!writing && pendingText == null) {
            writing = true
            message = null
            // Freeze the report before starting any background work.
            val text = report.text
            scope.launch {
                try {
                    val intent = NetworkDiagnosticFiles.shareIntent(context, text)
                    context.startActivity(Intent.createChooser(intent, "分享诊断文件"))
                    message = "请在分享界面选择接收人"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    message = "无法分享诊断文件，请先保存后发送"
                } finally { writing = false }
            }
        }
    })
}

internal data class NetworkDiagnosticExports(
    val busy: Boolean,
    val message: String?,
    val onSave: (NetworkDiagnosticReport) -> Unit,
    val onShare: (NetworkDiagnosticReport) -> Unit
)

internal val NetworkDiagnosticReportSaver = listSaver<NetworkDiagnosticReport?, String>(
    save = { report -> report?.let { listOf(it.generatedAt, it.summary, it.text) } ?: emptyList() },
    restore = { fields -> if (fields.size == 3) NetworkDiagnosticReport(fields[0], fields[1], fields[2]) else null }
)

@Composable internal fun NetworkDiagnosticsSection(exports: NetworkDiagnosticExports,
    completedReport: NetworkDiagnosticReport?, onReportCompleted: (NetworkDiagnosticReport) -> Unit) {
    val context = LocalContext.current.applicationContext
    val diagnostics = remember(context) { NetworkDiagnostics(context) }
    NetworkDiagnosticsContent(exports, diagnostics::snapshot, diagnostics::run, completedReport, onReportCompleted)
}

@Composable internal fun NetworkDiagnosticsContent(
    exports: NetworkDiagnosticExports,
    loadSnapshot: () -> NetworkDiagnosticReport,
    runDiagnostics: suspend ((String) -> Unit) -> NetworkDiagnosticReport,
    completedReport: NetworkDiagnosticReport? = null,
    onReportCompleted: (NetworkDiagnosticReport) -> Unit = {}
) {
    var initial by remember { mutableStateOf(completedReport) }
    var loadingError by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        if (initial != null) return@LaunchedEffect
        loadingError = false
        try {
            initial = withContext(Dispatchers.IO) { loadSnapshot() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { loadingError = true }
    }
    val report = initial
    if (report != null) {
        NetworkDiagnosticsPanel(report, runDiagnostics, exports.onSave, exports.onShare, exports.busy,
            exports.message, onReportCompleted)
    } else LedgerPanel {
        if (loadingError) {
            Text("暂时无法读取诊断信息，请重试", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重新读取") }
        } else {
            Text("正在收集本机网络信息…", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable internal fun NetworkDiagnosticsPanel(
    initialReport: NetworkDiagnosticReport,
    runDiagnostics: suspend ((String) -> Unit) -> NetworkDiagnosticReport,
    onSave: (NetworkDiagnosticReport) -> Unit,
    onShare: (NetworkDiagnosticReport) -> Unit,
    exportBusy: Boolean = false,
    exportMessage: String? = null,
    onReportCompleted: (NetworkDiagnosticReport) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf(initialReport) }
    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        LedgerPanel(accent = true) {
            Text("先回记账页重试失败的操作，再开始诊断并将文件发给维护者。", style = MaterialTheme.typography.bodyMedium)
            Text("诊断文件不含账号凭据与账本。公开服务探测不验证账号状态或 AI 结果。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LedgerPanel {
            Text(report.summary, style = MaterialTheme.typography.titleMedium)
            Text("报告时间：${report.generatedAt}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            progress?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!running) exportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Button(onClick = {
                if (!running && !exportBusy) {
                    running = true
                    error = false
                    progress = "正在准备诊断…"
                    val current = ++generation
                    job = scope.launch {
                        try {
                            val next = runDiagnostics { status ->
                                scope.launch { if (running && current == generation) progress = status }
                            }
                            coroutineContext.ensureActive()
                            report = next
                            onReportCompleted(next)
                            progress = "诊断已完成"
                        } catch (cancelled: CancellationException) {
                            progress = "诊断已取消"
                            throw cancelled
                        } catch (_: Exception) {
                            error = true
                            progress = "诊断未完成，请稍后重试"
                        } finally { running = false }
                    }
                }
            }, enabled = !running && !exportBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                Text("开始诊断")
            }
            if (running) OutlinedButton(onClick = { generation++; job?.cancel() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("取消诊断") }
            OutlinedButton(onClick = { onSave(report) }, enabled = !running && !exportBusy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("保存诊断文件") }
            OutlinedButton(onClick = { onShare(report) }, enabled = !running && !exportBusy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("分享诊断文件") }
            if (exportBusy) Text("正在准备诊断文件…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(if (expanded) "收起详细报告" else "查看详细报告")
            }
            if (expanded) SelectionContainer {
                Text(report.text, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
