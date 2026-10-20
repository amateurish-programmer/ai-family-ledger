package com.familyledger.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.familyledger.app.data.AppTheme
import com.familyledger.app.data.AppUpdateService
import com.familyledger.app.data.IdentityProfile
import com.familyledger.app.data.NetworkDiagnosticReport
import com.familyledger.app.data.XlsxCodec
import com.familyledger.app.domain.ImportBatch
import java.time.LocalDate

private enum class SettingsRoute(val title: String, val subtitle: String) {
    HOME("账本设置", "家庭、账本与使用偏好"),
    ROLE("角色设置", "角色、头像、家庭账号与同步"),
    HISTORY("历史账本", "导入、导出与历史整理"),
    BACKUP("备份与恢复", "妥善保存每一笔记录"),
    APPEARANCE("外观主题", "选择你喜欢的账本模样"),
    UPDATES("应用更新", "检查并安装新版本"),
    DIAGNOSTICS("网络诊断", "检查连接并分享诊断文件")
}

@Composable fun SettingsScreen(model: LedgerViewModel, state: LedgerState, focusUpdates: Boolean = false, onUpdatesFocused: () -> Unit = {}) {
    val resolver = LocalContext.current.contentResolver
    val style = LocalLedgerStyle.current
    var routeName by rememberSaveable { mutableStateOf(SettingsRoute.HOME.name) }
    val route = SettingsRoute.valueOf(routeName)
    // Keep every route's scroll state alive while its content is off screen.
    val homeScroll = rememberScrollState()
    val roleScroll = rememberScrollState()
    val historyScroll = rememberScrollState()
    val backupScroll = rememberScrollState()
    val appearanceScroll = rememberScrollState()
    val updatesScroll = rememberScrollState()
    val diagnosticsScroll = rememberScrollState()
    val diagnosticExports = rememberNetworkDiagnosticExports()
    var completedDiagnosticReport by rememberSaveable(stateSaver = NetworkDiagnosticReportSaver) {
        mutableStateOf<NetworkDiagnosticReport?>(null)
    }
    val scroll = when (route) {
        SettingsRoute.HOME -> homeScroll
        SettingsRoute.ROLE -> roleScroll
        SettingsRoute.HISTORY -> historyScroll
        SettingsRoute.BACKUP -> backupScroll
        SettingsRoute.APPEARANCE -> appearanceScroll
        SettingsRoute.UPDATES -> updatesScroll
        SettingsRoute.DIAGNOSTICS -> diagnosticsScroll
    }
    var rollback by remember { mutableStateOf<ImportBatch?>(null) }
    var role by rememberSaveable(state.localRole) { mutableStateOf(state.localRole) }
    var avatar by rememberSaveable(state.localAvatar) { mutableStateOf(state.localAvatar) }
    LaunchedEffect(focusUpdates) {
        if (focusUpdates) {
            routeName = SettingsRoute.UPDATES.name
            onUpdatesFocused()
        }
    }
    BackHandler(enabled = route != SettingsRoute.HOME) { routeName = SettingsRoute.HOME.name }

    // These launchers and the pending export effect must outlive secondary-page navigation.
    val importExcel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.previewImport(resolver, it) }; model.finishDocumentPicker(uri == null)
    }
    val exportExcel = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XlsxCodec.MIME)) { uri ->
        uri?.let { model.exportSpreadsheet(resolver, it) }; model.finishDocumentPicker(uri == null)
    }
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
    Column(Modifier.fillMaxSize().testTag("settings_page").imePadding()
        .padding(horizontal = style.pagePadding, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(style.sectionSpacing)) {
        if (route == SettingsRoute.HOME) {
            PageHeading(route.title, route.subtitle, syncing = state.syncing) { IconBadge(Icons.Outlined.Settings, sage = true) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { routeName = SettingsRoute.HOME.name }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回设置")
                }
                Box(Modifier.weight(1f)) { PageHeading(route.title, route.subtitle, syncing = state.syncing) }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(style.sectionSpacing)) {
            when (route) {
                SettingsRoute.HOME -> SettingsHome(state) { routeName = it.name }
                SettingsRoute.ROLE -> {
                    LedgerPanel(accent = true) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProfileBadge(IdentityProfile.avatars.firstOrNull { it.id == avatar } ?: IdentityProfile.avatars.first())
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(role.ifBlank { "我的角色" }, style = MaterialTheme.typography.titleLarge)
                                Text(state.cloudStatus?.email ?: "本机角色 · 账目默认归属", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    OutlinedTextField(role, { if (it.length <= 20) role = it }, label = { Text("角色名称") },
                        placeholder = { Text("例如：老公、老婆") }, singleLine = true,
                        supportingText = { Text("${role.length}/20 · 新账目默认归属此角色") },
                        enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    }
                    Button(onClick = { model.setLocalIdentity(role, avatar) },
                        enabled = !state.busy && role.isNotBlank() && (role.trim() != state.localRole || avatar != state.localAvatar),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("保存角色") }
                    SettingsNote("更改角色不会修改已有记录，也不会改变家庭权限。")
                    SectionHeading("家庭账号与同步")
                    LedgerPanel {
                        SettingsAction(Icons.Outlined.PeopleOutline, "管理家庭账号与同步",
                            state.cloudStatus?.familyName ?: "登录账号，与家人共享账本", !state.busy, model::openCloud)
                    }
                }
                SettingsRoute.HISTORY -> {
                    SettingsNote("Excel 适合整理与查看，换机请使用完整备份。")
                    LedgerPanel {
                        SettingsAction(Icons.Outlined.FileUpload, "导入随手记 Excel", ".xlsx · 人民币 · 先预览再导入", !state.busy && !state.loading) {
                            model.launchDocumentPicker { importExcel.launch(arrayOf(XlsxCodec.MIME, "application/octet-stream")) }
                        }
                        SettingsDivider()
                        SettingsAction(Icons.Outlined.FileDownload, "导出 Excel", "全部历史收支与余额变更 · 不含已删除记录", !state.busy && !state.loading) {
                            model.prepareSpreadsheetExport()
                        }
                        SettingsDivider()
                        SettingsAction(Icons.Outlined.History, "查看导入批次", "查看来源，按批次撤销导入", !state.busy, model::loadBatches)
                    }
                    state.batches.forEach { batch ->
                        LedgerPanel {
                            Text(batch.fileName, style = MaterialTheme.typography.titleSmall)
                            SettingsNote("共 ${batch.total} 条 · 当前 ${batch.active} 条")
                            TextButton(onClick = { rollback = batch }, enabled = batch.active > 0 && !state.busy,
                                modifier = Modifier.align(Alignment.End)) { Text("撤销此批次") }
                        }
                    }
                }
                SettingsRoute.BACKUP -> {
                    SettingsNote("卸载应用会删除本机数据，请定期备份。")
                    LedgerPanel {
                        SettingsAction(Icons.Outlined.SaveAlt, "导出完整备份", "包含全部账目详情的明文文件，请妥善保存", !state.busy && !state.loading) {
                            model.launchDocumentPicker { export.launch("家庭账本-${LocalDate.now()}.ledger.json") }
                        }
                        SettingsDivider()
                        SettingsAction(Icons.Outlined.Restore, "从备份恢复", "仅添加新 ID，不覆盖现有记录 · 最大 10 MB", !state.busy && !state.loading) {
                            model.launchDocumentPicker { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                        }
                    }
                }
                SettingsRoute.APPEARANCE -> {
                    ThemePicker()
                    SettingsNote("外观仅保存在本机，切换账号后仍会保留。")
                }
                SettingsRoute.UPDATES -> {
                    AppUpdateSection(ledgerBusy = state.busy || state.loading || state.documentPickerOpen || state.spreadsheetExportReady)
                    SettingsNote("登录并加入家庭后可使用记账与同步；已保存账目可离线查看和编辑。")
                }
                SettingsRoute.DIAGNOSTICS -> NetworkDiagnosticsSection(diagnosticExports, completedDiagnosticReport) {
                    completedDiagnosticReport = it
                }
            }
            Spacer(Modifier.height(8.dp))
        }
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

@Composable private fun SettingsHome(state: LedgerState, open: (SettingsRoute) -> Unit) {
    val theme = LocalAppTheme.current
    val style = LocalLedgerStyle.current
    val context = LocalContext.current.applicationContext
    val version = remember(context) { AppUpdateService(context).currentVersionName }
    Column(verticalArrangement = Arrangement.spacedBy(if (style.compact) 10.dp else 12.dp)) {
        LedgerPanel(accent = true, contentPadding = 16.dp, contentSpacing = 10.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProfileBadge(IdentityProfile.familyIcons.firstOrNull { it.id == (state.cloudStatus?.familyIcon ?: "home") }
                    ?: IdentityProfile.familyIcons.first())
                Text(state.cloudStatus?.familyName ?: "我的家庭账本", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (theme == AppTheme.WARM) FamilyIllustration(Modifier.size(width = 76.dp, height = 60.dp))
            }
            SettingsDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("本机 ${state.entries.size} 笔记录", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("我的角色 · ${state.localRole}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("家庭与账本", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LedgerPanel(contentPadding = if (style.compact) 12.dp else 16.dp, contentSpacing = 2.dp) {
            SettingsAction(Icons.Outlined.PersonOutline, "角色设置", "角色、头像、家庭账号与同步", true) { open(SettingsRoute.ROLE) }
            SettingsDivider()
            SettingsAction(Icons.Outlined.MenuBook, "历史账本", "导入、导出与历史整理", true) { open(SettingsRoute.HISTORY) }
            SettingsDivider()
            SettingsAction(Icons.Outlined.VerifiedUser, "备份与恢复", "妥善保存每一笔记录", true) { open(SettingsRoute.BACKUP) }
        }
        Text("使用偏好", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LedgerPanel(contentPadding = if (style.compact) 12.dp else 16.dp, contentSpacing = 2.dp) {
            SettingsAction(Icons.Outlined.Palette, "外观主题", theme.label, true) { open(SettingsRoute.APPEARANCE) }
            SettingsDivider()
            SettingsAction(Icons.Outlined.SystemUpdateAlt, "应用更新", "v$version", true) { open(SettingsRoute.UPDATES) }
            SettingsDivider()
            SettingsAction(Icons.Outlined.NetworkCheck, "网络诊断", "连接异常时，生成文件发给维护者", true) { open(SettingsRoute.DIAGNOSTICS) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.FavoriteBorder, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            SettingsNote("AI 家庭账本 · 记录生活的每一天")
        }
    }
}

@Composable private fun SettingsAction(icon: ImageVector, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    val style = LocalLedgerStyle.current
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 52.dp)
        .padding(vertical = if (style.compact) 2.dp else 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(if (style.compact) 21.dp else 23.dp),
            tint = if (enabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
            SettingsNote(subtitle)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SettingsDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
@Composable private fun SettingsNote(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
