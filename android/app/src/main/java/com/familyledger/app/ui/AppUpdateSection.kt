package com.familyledger.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familyledger.app.data.AppUpdateService
import java.util.Locale

@Composable fun AppUpdateSection(ledgerBusy: Boolean) {
    val context = LocalContext.current
    val service = remember(context.applicationContext) { AppUpdateService(context.applicationContext) }
    val model = rememberAppUpdateModel()
    val state by model.state.collectAsStateWithLifecycle()
    var showPermission by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.notice(if (context.packageManager.canRequestPackageInstalls()) "安装权限已开启，请点击安装更新"
            else "尚未允许安装，下载的安装包已保留")
    }
    LaunchedEffect(state.installRequest, ledgerBusy) {
        if (state.installRequest != null && !ledgerBusy) {
            val file = model.consumeInstallRequest() ?: return@LaunchedEffect
            if (!context.packageManager.canRequestPackageInstalls()) showPermission = true
            else try {
                context.startActivity(service.installationIntent(file))
                model.notice("请在系统界面确认安装；取消后可以再次点击安装更新")
            } catch (_: Exception) { model.notice("无法打开系统安装界面，请重试", error = true) }
        }
    }
    AppUpdateCard(model.currentVersionName, state, ledgerBusy, model::check, model::download, model::cancelDownload) {
        if (context.packageManager.canRequestPackageInstalls()) model.prepareInstall() else showPermission = true
    }
    if (showPermission) AlertDialog(onDismissRequest = { showPermission = false },
        title = { Text("允许安装应用更新") },
        text = { Text("请在系统设置中允许“家庭账本”安装应用。返回后点击“安装更新”，再由系统确认覆盖安装。") },
        confirmButton = { TextButton(onClick = {
            showPermission = false
            try { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
            catch (_: Exception) { model.notice("无法打开权限页面，请到系统设置中允许安装未知应用", error = true) }
        }) { Text("前往设置") } },
        dismissButton = { TextButton(onClick = { showPermission = false }) { Text("稍后再说") } })
}

@Composable internal fun AppUpdateCard(currentVersion: String, state: AppUpdateState, ledgerBusy: Boolean,
    onCheck: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit, onInstall: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("应用更新", style = MaterialTheme.typography.titleMedium)
            Text("当前版本 v$currentVersion", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.update?.let { update ->
                Text("发现新版本 v${update.versionName} · ${updateSize(update.sizeBytes)}", style = MaterialTheme.typography.titleSmall)
                Text(update.notes.ifBlank { "体验优化与问题修复" }, style = MaterialTheme.typography.bodyMedium)
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            when (state.phase) {
                UpdatePhase.CHECKING, UpdatePhase.VERIFYING, UpdatePhase.CANCELLING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.phase == UpdatePhase.CHECKING) Text("正在检查更新…", style = MaterialTheme.typography.bodySmall)
                }
                UpdatePhase.DOWNLOADING -> {
                    LinearProgressIndicator(progress = { if (state.total > 0) (state.received.toFloat() / state.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                    Text("已下载 ${updateSize(state.received)} / ${updateSize(state.total)}", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消下载") }
                }
                UpdatePhase.READY -> {
                    Button(onClick = onInstall, enabled = !ledgerBusy && state.installRequest == null, modifier = Modifier.fillMaxWidth()) { Text("安装更新") }
                    if (ledgerBusy) Text("账本正在处理数据，请完成后安装", style = MaterialTheme.typography.bodySmall)
                }
                UpdatePhase.AVAILABLE -> Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("下载更新") }
                UpdatePhase.IDLE -> Unit
            }
            if (!state.busy) OutlinedButton(onClick = onCheck, enabled = state.installRequest == null, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.update == null) "检查更新" else "重新检查更新")
            }
            Text("覆盖升级保留本机数据。安装需在系统界面确认。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun updateSize(bytes: Long): String = String.format(Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024.0))
