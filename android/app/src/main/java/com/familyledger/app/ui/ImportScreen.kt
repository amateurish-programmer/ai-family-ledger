package com.familyledger.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familyledger.app.domain.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ImportScreen(model: LedgerViewModel, state: LedgerState, snackbar: SnackbarHostState) {
    val preview = state.importPreview ?: return
    var filter by rememberSaveable { mutableStateOf("全部") }
    var confirm by remember { mutableStateOf(false) }
    BackHandler(enabled = !state.busy) { model.cancelImport() }
    val selected = preview.rows.filter { it.key in state.selectedImportKeys }.mapNotNull { it.entry }
    val income = selected.filter { it.type == EntryType.INCOME }.sumOf { it.amountMinor }
    val expense = selected.filter { it.type == EntryType.EXPENSE }.sumOf { it.amountMinor }
    Scaffold(topBar = { TopAppBar(title = { Text("导入预览") }, navigationIcon = {
        TextButton(onClick = model::cancelImport, enabled = !state.busy) { Text("取消") }
    }) }, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        Surface {
            Button(onClick = { confirm = true }, enabled = selected.isNotEmpty() && !state.busy,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                Text(if (state.busy) "正在导入…" else "导入所选 ${selected.size} 条")
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(preview.fileName, fontWeight = FontWeight.Bold)
                Text("${preview.rows.size} 条记录 · 已选 ${selected.size} 条")
                Text("所选收入 ¥ ${Money.format(income)}\n所选支出 ¥ ${Money.format(expense)}")
                Text("余额变更仅保留原值，不计收支。疑似重复默认不选，可逐条勾选保留。", style = MaterialTheme.typography.bodySmall)
                Text("导入仅新增，不会更新或删除旧账。双方都有时分秒时会区分不同时间；缺少时间仍提示疑似重复。", style = MaterialTheme.typography.bodySmall)
                if (preview.rows.none { it.status == ImportStatus.NEW }) {
                    Text(if (preview.rows.any { it.status == ImportStatus.SUSPECTED })
                        "没有默认新增项。请查看“疑似重复”：若确为另一笔交易，可逐条勾选导入。"
                    else "没有可自动选中的新增记录，请查看已处理和错误数量。", style = MaterialTheme.typography.bodySmall)
                }
                if (preview.ignoredSheets.isNotEmpty()) Text("未导入的工作表：${preview.ignoredSheets.joinToString()}" )
            }
            item {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("全部", "新增", "疑似重复").forEach { label -> FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("已处理", "错误").forEach { label -> FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) }) }
                    }
                }
                Text(ImportStatus.entries.joinToString(" · ") { s -> "${s.label} ${preview.rows.count { it.status == s }}" }, style = MaterialTheme.typography.bodySmall)
            }
            items(preview.rows.filter { filter == "全部" || it.status.label == filter }, key = { it.key }) { row ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp)) {
                        Checkbox(checked = row.key in state.selectedImportKeys, onCheckedChange = { model.toggleImport(row.key) },
                            enabled = !state.busy && row.status in listOf(ImportStatus.NEW, ImportStatus.SUSPECTED))
                        Column(Modifier.weight(1f)) {
                            Text("${row.sheet} · 第 ${row.rowNumber} 行 · ${row.status.label}", style = MaterialTheme.typography.labelLarge)
                            row.entry?.let { e ->
                                Text("${e.origin?.originalDate ?: e.occurredOn} · ¥ ${Money.format(e.amountMinor)}", fontWeight = FontWeight.Bold)
                                Text("${e.categoryL1} · ${e.account} · ${e.member}")
                                if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall)
                                if (row.status == ImportStatus.SUSPECTED) Text("与账本或本文件中的记录相似，不能确定重复；独立交易可勾选保留。", style = MaterialTheme.typography.bodySmall)
                            }
                            if (row.error.isNotBlank()) Text(row.error, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("确认导入？") },
        text = { Text("将新增所选 ${selected.size} 条记录。已处理的原始行自动跳过。错误行不会导入，疑似重复仅导入你勾选的行。") },
        confirmButton = { TextButton(enabled = !state.busy, onClick = { confirm = false; model.confirmImport() }) { Text("确认导入") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("返回检查") } })
}
