package com.familyledger.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable fun SettingsScreen(model: LedgerViewModel, state: LedgerState) {
    val resolver = LocalContext.current.contentResolver
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { model.export(resolver, it) }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.previewRestore(resolver, it) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("账本设置", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("本机账本 · ${state.entries.size} 笔记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text("数据备份", style = MaterialTheme.typography.titleLarge)
        Text("卸载应用会删除本机数据。请定期导出备份，并保存到可信位置。备份是明文文件，包含账目详情。")
        Button(onClick = { export.launch("家庭账本-${LocalDate.now()}.ledger.json") }, enabled = !state.busy && !state.loading,
            modifier = Modifier.fillMaxWidth()) { Text("导出完整备份") }
        OutlinedButton(onClick = { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            enabled = !state.busy && !state.loading, modifier = Modifier.fillMaxWidth()) { Text("从备份恢复") }
        Text("恢复只添加新 ID；已有记录及已删除记录不会被旧备份覆盖。最多支持 10 MB。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("当前版本", style = MaterialTheme.typography.titleLarge)
        Text("V0.1.0 · 离线记账版")
        Text("已提供手工记账、基础月报/年报和备份。")
        Text("Excel 导入、AI/语音记账和家庭云同步将在后续版本接入。当前记录仅保存在本机。",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    state.restorePreview?.let { rows ->
        AlertDialog(onDismissRequest = model::cancelRestore,
            title = { Text("恢复备份？") }, text = { Text("文件包含 ${rows.size} 条记录（含删除标记）。\n确认后添加新记录，跳过已有 ID。现有账目不会被覆盖。") },
            confirmButton = { TextButton(onClick = model::confirmRestore, enabled = !state.busy) { Text("确认恢复") } },
            dismissButton = { TextButton(onClick = model::cancelRestore, enabled = !state.busy) { Text("取消") } })
    }
}
