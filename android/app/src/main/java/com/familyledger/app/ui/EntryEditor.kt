package com.familyledger.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.familyledger.app.domain.*
import java.time.LocalDate
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun EntryEditor(existing: LedgerEntry?, busy: Boolean, snackbar: SnackbarHostState,
    onClose: () -> Unit, onSave: (LedgerEntry) -> Unit) {
    val id by rememberSaveable { mutableStateOf(existing?.id ?: UUID.randomUUID().toString()) }
    var typeName by rememberSaveable { mutableStateOf((existing?.type ?: EntryType.EXPENSE).name) }
    var amount by rememberSaveable { mutableStateOf(existing?.let { Money.format(it.amountMinor) } ?: "") }
    var date by rememberSaveable { mutableStateOf(existing?.occurredOn ?: LocalDate.now().toString()) }
    var category by rememberSaveable { mutableStateOf(existing?.categoryL1 ?: "食品酒水") }
    var subcategory by rememberSaveable { mutableStateOf(existing?.categoryL2 ?: "") }
    var account by rememberSaveable { mutableStateOf(existing?.account ?: "银行卡") }
    var member by rememberSaveable { mutableStateOf(existing?.member ?: "本人") }
    var recorder by rememberSaveable { mutableStateOf(existing?.recordedBy ?: "本人") }
    var merchant by rememberSaveable { mutableStateOf(existing?.merchant ?: "") }
    var project by rememberSaveable { mutableStateOf(existing?.project ?: "") }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    BackHandler { if (!busy) discard = true }

    Scaffold(topBar = { TopAppBar(title = { Text(if (existing == null) "记一笔" else "编辑记录") },
        navigationIcon = { IconButton(onClick = { discard = true }, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface {
                Button(onClick = {
                    try {
                        val entry = validateEntry(LedgerEntry(id = id, type = EntryType.valueOf(typeName),
                            occurredOn = validateDate(date.trim()), amountMinor = if (typeName == EntryType.BALANCE_ADJUSTMENT.name) java.math.BigDecimal(amount.trim()).movePointRight(2).longValueExact() else Money.parse(amount), categoryL1 = category,
                            categoryL2 = subcategory, account = account, member = member, recordedBy = recorder,
                            merchant = merchant, project = project, note = note, origin = existing?.origin))
                        error = null
                        onSave(entry)
                    } catch (e: ArithmeticException) { error = "金额超出范围或超过两位小数" }
                    catch (e: IllegalArgumentException) { error = e.message }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp).height(52.dp)) {
                    Text(if (busy) "正在保存…" else "保存记录")
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(EntryType.EXPENSE, EntryType.INCOME).forEach { type ->
                    FilterChip(selected = typeName == type.name, enabled = !busy, onClick = {
                        typeName = type.name
                        category = if (type == EntryType.INCOME) "职业收入" else "食品酒水"
                        subcategory = ""
                    }, label = { Text(type.label) })
                }
            }
            if (typeName == EntryType.BALANCE_ADJUSTMENT.name) Text("余额调整不参与收支统计")
            existing?.origin?.let { origin ->
                Text("来源：${origin.fileName} · ${origin.sheet} 第 ${origin.rowNumber} 行\n原始时间：${origin.originalDate}", style = MaterialTheme.typography.bodySmall)
                if (origin.account2.isNotEmpty()) Text("账户2：${origin.account2}", style = MaterialTheme.typography.bodySmall)
                if (origin.projectCategory.isNotEmpty()) Text("项目分类：${origin.projectCategory}", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(amount, { amount = it }, label = { Text("金额（元）") }, prefix = { Text("¥ ") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                enabled = !busy, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            EditorField("日期 · YYYY-MM-DD", date, busy) { date = it }
            EditorField("一级分类", category, busy) { category = it }
            EditorField("二级分类（可选）", subcategory, busy) { subcategory = it }
            EditorField("账户", account, busy) { account = it }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(member, { member = it }, label = { Text("归属成员") }, enabled = !busy, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(recorder, { recorder = it }, label = { Text("记账人") }, enabled = !busy, singleLine = true, modifier = Modifier.weight(1f))
            }
            EditorField("商家（可选）", merchant, busy) { merchant = it }
            EditorField("项目（可选）", project, busy) { project = it }
            OutlinedTextField(note, { note = it }, label = { Text("备注（可选）") }, minLines = 2, maxLines = 5,
                enabled = !busy, modifier = Modifier.fillMaxWidth())
            Text("记录保存在当前手机，可在设置中导出备份。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("退出编辑？") },
        text = { Text("尚未保存的修改将丢失。") },
        confirmButton = { TextButton(onClick = onClose) { Text("退出") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
}

@Composable private fun EditorField(label: String, value: String, busy: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
}
