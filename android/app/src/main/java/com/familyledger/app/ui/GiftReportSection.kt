package com.familyledger.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.familyledger.app.domain.*

@Composable
fun GiftReportSection(entries: List<LedgerEntry>, range: ReportRange, onOrganize: () -> Unit) {
    var allHistory by rememberSaveable { mutableStateOf(false) }
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    val report = remember(entries, range, allHistory) { buildGiftReport(entries, if (allHistory) null else range) }
    Column(Modifier.fillMaxWidth().testTag("gift_report"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeading("人情往来", "仅统计已确认的人情收支 · 人民币")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(selected = !allHistory, onClick = { allHistory = false }, label = { Text("当前期间") })
            FilterChip(selected = allHistory, onClick = { allHistory = true }, label = { Text("全部历史") })
        }
        Text(if (allHistory) "全部历史记录" else "${range.start} 至 ${range.endExclusive.minusDays(1)}", style = MaterialTheme.typography.bodySmall)
        Text("收到人情  ¥ ${Money.format(report.income)}", modifier = Modifier.testTag("gift_income"))
        Text("送出人情  ¥ ${Money.format(report.expense)}", modifier = Modifier.testTag("gift_expense"))
        Text("往来差额  ¥ ${Money.format(report.difference)}", modifier = Modifier.testTag("gift_difference"))
        Text("差额为收到减送出，不代表欠款或账户余额。", style = MaterialTheme.typography.bodySmall)
        if (report.entries.isEmpty()) Text("这个范围内暂无已确认的人情记录。")
        GiftRanking("收到人情排行", "income", report.incomeRanking) { selectedName = it }
        GiftRanking("送出人情排行", "expense", report.expenseRanking) { selectedName = it }
        if (report.pendingEntries.isNotEmpty()) {
            TextButton(onClick = { selectedName = "" }, modifier = Modifier.testTag("gift_pending")) {
                Text("待补全对象 · ${report.pendingEntries.size} 笔（已计入合计，不参与排行）")
            }
        }
        OutlinedButton(onClick = onOrganize, modifier = Modifier.fillMaxWidth().testTag("gift_organize")) { Text("整理历史人情") }
    }
    selectedName?.let { name ->
        val rows = report.forCounterparty(name)
        AlertDialog(onDismissRequest = { selectedName = null }, title = { Text(if (name.isEmpty()) "待补全对象" else name) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp).testTag("gift_detail"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("${if (allHistory) "全部历史" else "当前期间"} · ${rows.size} 笔") }
                    items(rows, key = { it.id }) { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${entry.occurredOn} · ${entry.type.label} ¥ ${Money.format(entry.amountMinor)}")
                            Text("${entry.categoryL1} · ${entry.member} · ${entry.account}", style = MaterialTheme.typography.bodySmall)
                            if (entry.note.isNotBlank()) Text(entry.note, style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { selectedName = null }) { Text("关闭") } })
    }
}

@Composable
private fun GiftRanking(title: String, tag: String, ranking: List<CategoryTotal>, onSelect: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    if (ranking.isEmpty()) Text("暂无已补全对象的记录", style = MaterialTheme.typography.bodySmall)
    var visibleCount by rememberSaveable(tag) { mutableStateOf(20) }
    ranking.take(visibleCount).forEachIndexed { index, item ->
        TextButton(onClick = { onSelect(item.name) }, modifier = Modifier.fillMaxWidth().testTag("gift_${tag}_$index")) {
            Text("${index + 1}. ${item.name}", modifier = Modifier.weight(1f))
            Text("¥ ${Money.format(item.amount)}")
        }
    }
    if (visibleCount < ranking.size) TextButton(onClick = { visibleCount += 20 }) {
        Text("查看更多（已显示 ${minOf(visibleCount, ranking.size)} / ${ranking.size} 位）")
    }
}
