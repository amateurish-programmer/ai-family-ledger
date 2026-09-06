package com.familyledger.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.familyledger.app.data.CloudConflict
import com.familyledger.app.domain.Money

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CloudScreen(model: LedgerViewModel, state: LedgerState, snackbar: SnackbarHostState) {
    val status = state.cloudStatus
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var familyName by rememberSaveable { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var syncConfirm by remember { mutableStateOf(false) }
    var autoConfirm by remember { mutableStateOf(false) }
    var resolution by remember { mutableStateOf<Pair<CloudConflict, Boolean>?>(null) }
    val clipboard = LocalClipboardManager.current
    BackHandler(enabled = !state.busy) { model.closeCloud() }
    Scaffold(topBar = { TopAppBar(title = { Text("家庭与云端") }, navigationIcon = {
        IconButton(onClick = model::closeCloud, enabled = !state.busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
    }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.operationStatus ?: "正在处理…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Outlined.Cottage, null, Modifier.size(34.dp), tint = LedgerSage)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("与家人共享账本", style = MaterialTheme.typography.titleLarge)
                        Text("创建家庭，或使用家人的邀请码加入。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (status == null) Text("账号服务暂不可用，本机记账仍可使用。请查看错误提示后重试。")
            if (status?.email == null) {
                SectionHeading("登录家庭账号", "登录后即可对话记账与同步")
                OutlinedTextField(email, { email = it }, label = { Text("邮箱") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true,
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { model.login(email, password, false); password = "" }, enabled = !state.busy && status?.configured == true, modifier = Modifier.weight(1f)) { Text("登录") }
                    OutlinedButton(onClick = { model.login(email, password, true); password = "" }, enabled = !state.busy && status?.configured == true, modifier = Modifier.weight(1f)) { Text("注册") }
                }
                Text("注册后按邮件确认，再返回登录。", style = MaterialTheme.typography.bodySmall)
            } else {
                SectionHeading("当前账号", status.email)
                OutlinedButton(onClick = model::logout, enabled = !state.busy) { Text("退出登录") }
                if (status.familyId == null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SectionHeading("创建你的家庭")
                    OutlinedTextField(familyName, { familyName = it }, label = { Text("新家庭名称") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { model.createFamily(familyName) }, enabled = !state.busy && familyName.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("创建家庭") }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SectionHeading("加入已有家庭", "使用家人发来的邀请码")
                    OutlinedTextField(invite, { invite = it }, label = { Text("邀请码") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { model.joinFamily(invite) }, enabled = !state.busy && invite.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("加入家庭") }
                } else {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SectionHeading(status.familyName ?: "我的家庭", "同步前由你确认，冲突由你选择")
                    Text("此本机账本已绑定该家庭。为防止串账，不支持直接切换家庭；换家庭前请先导出备份并使用独立应用数据。", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { syncConfirm = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("同步家庭账本") }
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("打开 App 时同步", style = MaterialTheme.typography.titleSmall)
                            Text("自动更新家庭账本的本机副本", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.autoSync, onCheckedChange = { if (it) autoConfirm = true else model.setAutoSync(false) }, enabled = !state.busy)
                    }
                    if (status.lastSync > 0) Text("上次同步：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(status.lastSync))}", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = model::createInvite, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("生成家庭邀请码（管理员）") }
                    state.inviteCode?.let { code ->
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                            Text(code, Modifier.fillMaxWidth().padding(20.dp), style = MaterialTheme.typography.titleLarge)
                        }
                        TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text("复制邀请码") }
                    }
                }
            }
            if (state.cloudConflicts.isNotEmpty()) SectionHeading("待处理的同步冲突", "请比较本机与云端内容，再选择保留版本。")
            state.cloudConflicts.forEach { c ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${c.local.occurredOn} · ${c.local.categoryL1}")
                        Text("本机：${if (c.local.deletedAt != null) "已删除" else "¥ ${Money.format(c.local.amountMinor)}"} ${c.local.note}")
                        Text("云端：${if (c.remote.deletedAt != null) "已删除" else "¥ ${Money.format(c.remote.amountMinor)}"} ${c.remote.note}")
                        Row { TextButton(onClick = { resolution = c to false }, enabled = !state.busy) { Text("保留本机") }
                            TextButton(onClick = { resolution = c to true }, enabled = !state.busy) { Text("使用云端") } }
                    }
                }
            }
        }
    }
    if (syncConfirm || autoConfirm) AlertDialog(onDismissRequest = { syncConfirm = false; autoConfirm = false }, title = { Text("同步这个家庭的账目？") },
        text = { Text("会上传本机全部账目（含导入来源与删除标记），并下载家庭成员的账目。家庭成员可读写共享账本。冲突会保留到你明确选择，不会自动覆盖。") },
        confirmButton = { TextButton(onClick = { if (autoConfirm) model.setAutoSync(true) else model.syncCloud(); syncConfirm = false; autoConfirm = false }) { Text("确认") } },
        dismissButton = { TextButton(onClick = { syncConfirm = false; autoConfirm = false }) { Text("取消") } })
    resolution?.let { (conflict, remote) -> AlertDialog(onDismissRequest = { resolution = null }, title = { Text("确认处理冲突？") },
        text = { Text(if (remote) "使用云端整条记录覆盖本机记录，包括删除状态。" else "将本机整条记录提交到云端，包括删除状态。若云端再次更新，会重新提示冲突。") },
        confirmButton = { TextButton(onClick = { resolution = null; model.resolveConflict(conflict, remote) }) { Text("确认选择") } },
        dismissButton = { TextButton(onClick = { resolution = null }) { Text("取消") } }) }
}
