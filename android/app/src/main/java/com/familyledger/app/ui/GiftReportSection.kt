package com.familyledger.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familyledger.app.domain.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GiftReportSection(entries: List<LedgerEntry>, range: ReportRange, onOrganize: () -> Unit) {
    var allHistory by rememberSaveable { mutableStateOf(false) }
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    val report = remember(entries, range, allHistory) { buildGiftReport(entries, if (allHistory) null else range) }
    val style = LocalLedgerStyle.current
    Column(Modifier.fillMaxWidth().testTag("gift_report"), verticalArrangement = Arrangement.spacedBy(style.sectionSpacing)) {
        LedgerPanel(accent = true) {
            Text("人情往来", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = !allHistory, onClick = { allHistory = false }, label = { Text("当前期间") })
                FilterChip(selected = allHistory, onClick = { allHistory = true }, label = { Text("全部历史") })
            }
            Text(if (allHistory) "全部历史记录" else "${range.start} 至 ${range.endExclusive.minusDays(1)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("收到人情  ¥ ${Money.format(report.income)}", modifier = Modifier.testTag("gift_income"),
                color = LedgerSage, style = MaterialTheme.typography.titleMedium, fontFamily = style.moneyFontFamily)
            Text("送出人情  ¥ ${Money.format(report.expense)}", modifier = Modifier.testTag("gift_expense"),
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium, fontFamily = style.moneyFontFamily)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("往来差额  ¥ ${Money.format(report.difference)}", modifier = Modifier.testTag("gift_difference"),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, fontFamily = style.moneyFontFamily)
            Text("差额为收到减送出，不代表欠款或账户余额。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (report.pendingEntries.isNotEmpty()) {
            LedgerPanel {
                TextButton(onClick = { selectedName = "" }, modifier = Modifier.fillMaxWidth().testTag("gift_pending")) {
                    Text("待补全对象 · ${report.pendingEntries.size} 笔", Modifier.weight(1f))
                    Text("查看")
                }
                Text("已计入合计，补全对象后参与排行。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (report.entries.isEmpty()) EmptyLedger("暂无已确认的人情记录", "确认人情收支后，在这里查看亲友往来。")
        LedgerPanel { GiftRanking("收到人情排行", "income", report.incomeRanking) { selectedName = it } }
        LedgerPanel { GiftRanking("送出人情排行", "expense", report.expenseRanking) { selectedName = it } }
        OutlinedButton(onClick = onOrganize, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gift_organize")) { Text("整理历史人情") }
    }
    selectedName?.let { name ->
        val rows = report.forCounterparty(name)
        ModalBottomSheet(onDismissRequest = { selectedName = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = style.pagePadding).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (name.isEmpty()) "待补全对象" else name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { selectedName = null }) { Text("关闭") }
                }
                Text("${if (allHistory) "全部历史" else "当前期间"} · ${rows.size} 笔", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp).testTag("gift_detail"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(rows, key = { it.id }) { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("${entry.occurredOn} · ${entry.type.label} ¥ ${Money.format(entry.amountMinor)}",
                                style = MaterialTheme.typography.titleSmall, fontFamily = style.moneyFontFamily)
                            Text("${entry.categoryL1} · ${entry.member} · ${entry.account}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (entry.note.isNotBlank()) Text(entry.note, style = MaterialTheme.typography.bodyMedium)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GiftRanking(title: String, tag: String, ranking: List<CategoryTotal>, onSelect: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (ranking.isEmpty()) Text("暂无已补全对象的记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    var visibleCount by rememberSaveable(tag) { mutableStateOf(20) }
    ranking.take(visibleCount).forEachIndexed { index, item ->
        TextButton(onClick = { onSelect(item.name) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gift_${tag}_$index"),
            contentPadding = PaddingValues(vertical = 8.dp)) {
            Text("${index + 1}".padStart(2, '0'), Modifier.padding(end = 14.dp), style = MaterialTheme.typography.labelMedium)
            RankedAmount(item.name, "¥ ${Money.format(item.amount)}", Modifier.weight(1f),
                color = if (tag == "income") LedgerSage else MaterialTheme.colorScheme.primary)
        }
    }
    if (visibleCount < ranking.size) TextButton(onClick = { visibleCount += 20 }) {
        Text("查看更多（已显示 ${minOf(visibleCount, ranking.size)} / ${ranking.size} 位）")
    }
}
