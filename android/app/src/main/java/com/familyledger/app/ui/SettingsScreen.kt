package com.familyledger.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import com.familyledger.app.data.XlsxCodec
import com.familyledger.app.domain.ImportBatch

@Composable fun SettingsScreen(model: LedgerViewModel, state: LedgerState) {
    val resolver = LocalContext.current.contentResolver
    var rollback by remember { mutableStateOf<ImportBatch?>(null) }
    var role by remember(state.localRole) { mutableStateOf(state.localRole) }
    val importExcel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { model.previewImport(resolver, it) } }
    val exportExcel = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XlsxCodec.MIME)) { uri -> uri?.let { model.exportSpreadsheet(resolver, it) } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { model.export(resolver, it) }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.previewRestore(resolver, it) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("账本设置", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("本机账本 · ${state.entries.size} 笔记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("本机角色", style = MaterialTheme.typography.titleLarge)
        Text(state.cloudStatus?.email ?: "未登录 · 本机角色", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(role, { if (it.length <= 20) role = it }, label = { Text("角色名称，例如老公、老婆") }, singleLine = true,
            enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        Button(onClick = { model.setLocalRole(role) }, enabled = !state.busy && role.isNotBlank() && role.trim() != state.localRole) { Text("保存角色") }
        Text("新账目默认归属此角色；更改不会修改已有记录，也不会改变家庭权限。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = model::openCloud, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("家庭账号与同步") }
        Spacer(Modifier.height(8.dp))
        Text("历史账本", style = MaterialTheme.typography.titleLarge)
        Button(onClick = { importExcel.launch(arrayOf(XlsxCodec.MIME, "application/octet-stream")) }, enabled = !state.busy && !state.loading,
            modifier = Modifier.fillMaxWidth()) { Text("导入随手记 Excel") }
        OutlinedButton(onClick = { exportExcel.launch("家庭账本-${LocalDate.now()}.xlsx") }, enabled = !state.busy && !state.loading,
            modifier = Modifier.fillMaxWidth()) { Text("导出 Excel") }
        Text("支持 .xlsx、人民币；先预览再导入。Excel 不含删除标记及记录 ID，换机请使用完整备份。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = model::loadBatches, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("查看导入批次") }
        state.batches.forEach { batch ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(batch.fileName)
                    Text("共 ${batch.total} 条 · 当前 ${batch.active} 条", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { rollback = batch }, enabled = batch.active > 0 && !state.busy) { Text("撤销此批次") }
                }
            }
        }
        HorizontalDivider()
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
        Text("V0.7.0 · 家庭账本")
        Text("文字对话记账、账本问答、家庭角色、Excel 导入导出、月报/年报与备份。")
        Text("登录并加入家庭后，可使用对话与家庭同步。已保存账目可离线查看和编辑。",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    rollback?.let { batch -> AlertDialog(onDismissRequest = { rollback = null }, title = { Text("撤销导入批次？") },
        text = { Text("将删除此批次当前 ${batch.active} 条记录，包括导入后编辑过的记录。其他账目不受影响。同一文件再次导入仍会显示已处理。") },
        confirmButton = { TextButton(onClick = { rollback = null; model.rollbackBatch(batch.id) }, enabled = !state.busy) { Text("确认撤销") } },
        dismissButton = { TextButton(onClick = { rollback = null }) { Text("取消") } }) }
    state.restorePreview?.let { rows ->
        AlertDialog(onDismissRequest = model::cancelRestore,
            title = { Text("恢复备份？") }, text = { Text("文件包含 ${rows.size} 条记录（含删除标记）。\n确认后添加新记录，跳过已有 ID。现有账目不会被覆盖。") },
            confirmButton = { TextButton(onClick = model::confirmRestore, enabled = !state.busy) { Text("确认恢复") } },
            dismissButton = { TextButton(onClick = model::cancelRestore, enabled = !state.busy) { Text("取消") } })
    }
}
