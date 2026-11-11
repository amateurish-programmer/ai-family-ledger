package com.familyledger.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.familyledger.app.data.ChatCodec
import com.familyledger.app.data.ChatQuery
import com.familyledger.app.domain.*
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatQueryDetailsSheet(queries: List<ChatQuery>, entries: List<LedgerEntry>, onClose: () -> Unit) {
    var pending by remember { mutableStateOf(false) }
    val confirmed = remember(queries, entries) { queries.map { ChatCodec.select(it, entries) } }
    val uncertain = remember(queries, entries) { queries.map { ChatCodec.uncertain(it, entries) } }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().testTag("chat_query_details"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("查询明细", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose) { Text("关闭") }
                }
                Text("按当前已保存账目重新计算", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!pending, { pending = false }, label = { Text("已确认") })
                    if (uncertain.any { it.isNotEmpty() })
                        FilterChip(pending, { pending = true }, label = { Text("待核对") })
                }
                if (pending) Text("这些记录用途不明确，核对后可在账本中修改分类。", style = MaterialTheme.typography.bodySmall)
            }
            queries.forEachIndexed { index, query ->
                val rows = (if (pending) uncertain[index] else confirmed[index])
                    .sortedWith(compareByDescending<LedgerEntry> { it.occurredOn }.thenBy { it.id })
                item {
                    Text(query.label.ifBlank { "查询 ${index + 1}" }, style = MaterialTheme.typography.titleMedium)
                    Text("${query.start} 至 ${LocalDate.parse(query.end).minusDays(1)} · ${query.member.ifBlank { "全家" }}",
                        style = MaterialTheme.typography.bodySmall)
                    if (pending) Text("待核对 ${rows.size} 笔")
                    else {
                        val income = rows.filter { it.type == EntryType.INCOME }.fold(0L) { sum, row -> Math.addExact(sum, row.amountMinor) }
                        val expense = rows.filter { it.type == EntryType.EXPENSE }.fold(0L) { sum, row -> Math.addExact(sum, row.amountMinor) }
                        val totals = when (query.type) {
                            "EXPENSE" -> "支出 ¥ ${Money.format(expense)}"
                            "INCOME" -> "收入 ¥ ${Money.format(income)}"
                            else -> "收入 ¥ ${Money.format(income)} · 支出 ¥ ${Money.format(expense)}"
                        }
                        Text("$totals · ${rows.size} 笔", style = MaterialTheme.typography.titleMedium)
                    }
                    if (rows.isEmpty()) Text("此范围内没有匹配的已记账记录。")
                }
                items(rows, key = { "$index:${it.id}" }) { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${row.occurredOn} · ${row.type.label} ¥ ${Money.format(row.amountMinor)}")
                        Text(listOf(row.member, row.categoryL1, row.categoryL2, row.merchant).filter(String::isNotBlank).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall)
                        if (row.note.isNotBlank()) Text(row.note, style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
