package com.familyledger.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familyledger.app.data.GiftHistoryCodec
import com.familyledger.app.domain.Money

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun GiftHistoryScreen(model: LedgerViewModel, state: LedgerState, snackbar: SnackbarHostState, onClose: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val proposals = state.giftProposals
    val selected = proposals.orEmpty().count { it.selected }
    val candidates = remember(state.entries, state.selectedGiftCategories) {
        GiftHistoryCodec.candidates(state.entries, state.selectedGiftCategories).size
    }
    BackHandler { if (!state.busy) onClose() }
    Scaffold(topBar = { TopAppBar(title = { Text("历史礼金整理") }, navigationIcon = {
        TextButton(onClick = onClose, enabled = !state.busy) { Text("取消") }
    }) }, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        Surface {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.giftAnalyzing) {
                    Text(state.operationStatus ?: "正在整理…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick = model::cancelGiftAnalysis, modifier = Modifier.fillMaxWidth()) { Text("停止整理") }
                } else if (proposals == null) {
                    Button(onClick = model::analyzeGiftHistory, enabled = !state.busy && candidates > 0,
                        modifier = Modifier.fillMaxWidth()) { Text("AI 整理 $candidates 条历史记录") }
                } else {
                    TextButton(onClick = model::resetGiftProposals, enabled = !state.busy) { Text("返回选择分类 / 重新整理") }
                    Button(onClick = { confirm = true }, enabled = !state.busy && selected > 0,
                        modifier = Modifier.fillMaxWidth()) { Text("确认标注所选 $selected 条") }
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(if (proposals == null) "选择需要核对的分类" else "逐条核对礼金与往来人", fontWeight = FontWeight.Bold)
                Text("整理全部历史中未标注礼金或往来人待补充的收支。AI 每批最多处理 20 条，只发送记录 ID、商家与备注。")
                Text("勾选代表确认为礼金；往来人无法识别时保留空白，归入“待补充”。确认后只修改原记录礼金标记和往来人。", style = MaterialTheme.typography.bodySmall)
            }
            if (proposals == null) {
                if (state.giftCategories.isEmpty()) item { Text("没有待整理的历史收支。") }
                items(state.giftCategories) { category ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = category in state.selectedGiftCategories,
                            onCheckedChange = { model.toggleGiftCategory(category) }, enabled = !state.busy)
                        Text(category.label, Modifier.weight(1f))
                    }
                }
            } else {
                item { Text("共 ${proposals.size} 条提案 · 已选 $selected 条；尚未保存") }
                items(proposals, key = { it.original.id }) { proposal ->
                    val row = proposal.original
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = proposal.selected, enabled = !state.busy, onCheckedChange = {
                                    model.updateGiftProposal(row.id, it, proposal.counterparty)
                                })
                                Text("${row.occurredOn} · ${row.type.label} ¥ ${Money.format(row.amountMinor)}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            }
                            Text(listOf(row.categoryL1, row.categoryL2).filter { it.isNotBlank() }.joinToString(" / "))
                            if (row.merchant.isNotBlank()) Text("商家：${row.merchant}")
                            Text(row.note.ifBlank { "无备注" }, style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(value = proposal.counterparty,
                                onValueChange = { model.updateGiftProposal(row.id, proposal.selected, it) },
                                enabled = !state.busy, label = { Text("往来人（空白为待补充）") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { if (!state.busy) confirm = false },
        title = { Text("确认标注历史礼金？") },
        text = { Text("将所选 $selected 条原记录标为礼金，保存核对后的往来人。金额、日期、收支类型和导入来源保持原值；任何原记录已变化时，本次全部不写入，请重新整理。") },
        confirmButton = { TextButton(enabled = !state.busy, onClick = { confirm = false; model.confirmGiftHistory() }) { Text("确认保存") } },
        dismissButton = { TextButton(enabled = !state.busy, onClick = { confirm = false }) { Text("继续核对") } })
}
