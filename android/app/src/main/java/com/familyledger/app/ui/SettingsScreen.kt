package com.familyledger.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.familyledger.app.data.IdentityProfile
import com.familyledger.app.domain.ImportBatch
import kotlinx.coroutines.flow.first

@Composable fun SettingsScreen(model: LedgerViewModel, state: LedgerState, focusUpdates: Boolean = false, onUpdatesFocused: () -> Unit = {}) {
    val resolver = LocalContext.current.contentResolver
    val scroll = rememberScrollState()
    LaunchedEffect(focusUpdates) {
        if (focusUpdates) {
            snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }
            scroll.animateScrollTo(scroll.maxValue)
            onUpdatesFocused()
        }
    }
    var rollback by remember { mutableStateOf<ImportBatch?>(null) }
    var role by remember(state.localRole) { mutableStateOf(state.localRole) }
    var avatar by remember(state.localAvatar) { mutableStateOf(state.localAvatar) }
    val importExcel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { model.previewImport(resolver, it) }; model.finishDocumentPicker(uri == null) }
    val exportExcel = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XlsxCodec.MIME)) { uri -> uri?.let { model.exportSpreadsheet(resolver, it) }; model.finishDocumentPicker(uri == null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { model.export(resolver, it) }; model.finishDocumentPicker(uri == null)
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.previewRestore(resolver, it) }; model.finishDocumentPicker(uri == null)
    }
    LaunchedEffect(state.spreadsheetExportReady, state.busy) {
        if (state.spreadsheetExportReady && !state.busy) {
            model.launchPreparedSpreadsheet { exportExcel.launch("家庭账本-全部历史-${LocalDate.now()}.xlsx") }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).imePadding().padding(horizontal = 22.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeading("账本设置", "管理你的家庭、角色与数据", syncing = state.syncing) { IconBadge(Icons.Outlined.Settings, sage = true) }
        Spacer(Modifier.height(4.dp))
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ProfileBadge(IdentityProfile.familyIcons.first { it.id == (state.cloudStatus?.familyIcon ?: "home") })
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.cloudStatus?.familyName ?: "我的家庭账本", style = MaterialTheme.typography.titleMedium)
                    Text("本机已保存 ${state.entries.size} 笔记录", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        SectionHeading("我在家里的角色", "${state.cloudStatus?.email ?: "未登录 · 独立本机角色"}")
        OutlinedTextField(role, { if (it.length <= 20) role = it }, label = { Text("角色名称") },
            placeholder = { Text("例如：老公、老婆") }, singleLine = true,
            supportingText = { Text("${role.length}/20 · 新账目默认归属此角色") },
            enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        Text("角色头像", style = MaterialTheme.typography.titleSmall)
        IdentityProfile.avatars.chunked(3).forEach { choices ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { choice ->
                    FilterChip(selected = avatar == choice.id, onClick = { avatar = choice.id },
                        enabled = !state.busy, label = { Text("${choice.symbol} ${choice.label}") },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                }
            }
        }
        Button(onClick = { model.setLocalIdentity(role, avatar) }, enabled = !state.busy && role.isNotBlank() && (role.trim() != state.localRole || avatar != state.localAvatar),
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("保存角色") }
        Text("更改角色不会修改已有记录，也不会改变家庭权限。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsAction(Icons.Outlined.PeopleOutline, "家庭账号与同步", state.cloudStatus?.familyName ?: "登录账号，与家人共享账本", !state.busy, model::openCloud)
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SectionHeading("历史账本", "Excel 适合整理与查看，换机请使用完整备份。")
        SettingsAction(Icons.Outlined.FileUpload, "导入随手记 Excel", ".xlsx · 人民币 · 先预览再导入", !state.busy && !state.loading) {
            model.launchDocumentPicker { importExcel.launch(arrayOf(XlsxCodec.MIME, "application/octet-stream")) }
        }
        SettingsAction(Icons.Outlined.FileDownload, "导出 Excel", "全部历史收支与余额变更 · 不含已删除记录", !state.busy && !state.loading) {
            model.prepareSpreadsheetExport()
        }
        SettingsAction(Icons.Outlined.History, "查看导入批次", "查看来源，按批次撤销导入", !state.busy, model::loadBatches)
        state.batches.forEach { batch ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(batch.fileName, style = MaterialTheme.typography.titleSmall)
                    Text("共 ${batch.total} 条 · 当前 ${batch.active} 条", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { rollback = batch }, enabled = batch.active > 0 && !state.busy, modifier = Modifier.align(Alignment.End)) { Text("撤销此批次") }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SectionHeading("备份与恢复", "卸载应用会删除本机数据，请定期备份。")
        SettingsAction(Icons.Outlined.SaveAlt, "导出完整备份", "包含全部账目详情的明文文件，请妥善保存", !state.busy && !state.loading) {
            model.launchDocumentPicker { export.launch("家庭账本-${LocalDate.now()}.ledger.json") }
        }
        SettingsAction(Icons.Outlined.Restore, "从备份恢复", "仅添加新 ID，不覆盖现有记录 · 最大 10 MB", !state.busy && !state.loading) {
            model.launchDocumentPicker { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        AppUpdateSection(ledgerBusy = state.busy || state.loading || state.documentPickerOpen || state.spreadsheetExportReady)
        Text("家庭账本", style = MaterialTheme.typography.titleSmall)
        Text("文字对话记账 · 家庭财务分析 · 家庭同步", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("登录并加入家庭后可使用对话与同步；已保存账目可离线查看和编辑。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
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

@Composable private fun SettingsAction(icon: ImageVector, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = if (enabled) LedgerSage else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
