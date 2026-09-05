package com.familyledger.app.ui

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
import java.time.YearMonth

@Composable fun ReportsScreen(entries: List<LedgerEntry>, month: YearMonth, onMonth: (YearMonth) -> Unit) {
    var yearly by rememberSaveable { mutableStateOf(false) }
    val start = if (yearly) month.atDay(1).withDayOfYear(1) else month.atDay(1)
    val end = if (yearly) start.plusYears(1) else start.plusMonths(1)
    val report = remember(entries, start, end) { summarize(entries, start, end) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("收支报告", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(selected = !yearly, onClick = { yearly = false }, label = { Text("月度") })
                FilterChip(selected = yearly, onClick = { yearly = true }, label = { Text("年度") })
            }
            PeriodSelector(if (yearly) "${month.year} 年" else "${month.year} 年 ${month.monthValue} 月",
                { onMonth(if (yearly) month.minusYears(1) else month.minusMonths(1)) },
                { onMonth(if (yearly) month.plusYears(1) else month.plusMonths(1)) })
        }
        item { SummaryBlock(report) }
        item {
            Text("支出去向", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("按一级分类统计 · 人民币", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (report.categories.isEmpty()) item { Text("这段时间还没有支出记录。") }
        items(report.categories, key = { it.name }) { category ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(category.name, modifier = Modifier.weight(1f))
                    Text("¥ ${Money.format(category.amount)}", fontWeight = FontWeight.Medium)
                }
                LinearProgressIndicator(progress = { (category.amount.toDouble() / report.expense.coerceAtLeast(1)).toFloat() },
                    modifier = Modifier.fillMaxWidth().height(6.dp))
            }
        }
        item { Text("结余为本期收入减支出，不代表账户余额。余额调整不计入本报告。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
