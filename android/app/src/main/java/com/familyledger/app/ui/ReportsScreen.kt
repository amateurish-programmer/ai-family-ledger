package com.familyledger.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familyledger.app.domain.*
import java.time.YearMonth

@Composable fun ReportsScreen(entries: List<LedgerEntry>, month: YearMonth, onMonth: (YearMonth) -> Unit, model: LedgerViewModel, state: LedgerState) {
    var yearly by rememberSaveable { mutableStateOf(false) }
    val start = if (yearly) month.atDay(1).withDayOfYear(1) else month.atDay(1)
    val end = if (yearly) start.plusYears(1) else start.plusMonths(1)
    val complete = remember(entries, month, yearly) { buildReport(entries, month, yearly) }
    val report = complete.summary
    val reportKey = "$month|$yearly|${entries.hashCode()}"
    val context = LocalContext.current
    var requestAi by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { model.exportReport(context.contentResolver, it, exportText) }
    }
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
            Text("${complete.count} 笔收支 · 上期支出 ¥ ${Money.format(complete.previousExpense)}")
            val difference = Math.subtractExact(report.expense, complete.previousExpense)
            Text("较上期${if (difference >= 0) "增加" else "减少"} ¥ ${Money.format(kotlin.math.abs(difference))}", style = MaterialTheme.typography.bodySmall)
        }
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
        item { Text("成员支出", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        items(complete.members, key = { "member:${it.name}" }) { member ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(member.name, Modifier.weight(1f)); Text("¥ ${Money.format(member.amount)}")
            }
        }
        item { Text(if (yearly) "全年月度趋势" else "近六个月趋势", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        items(complete.trend, key = { "trend:${it.period}" }) { period ->
            val maximum = complete.trend.maxOfOrNull { maxOf(it.income, it.expense) }?.coerceAtLeast(1) ?: 1
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(period.period, fontWeight = FontWeight.Medium)
                Text("收入 ${Money.format(period.income)} · 支出 ${Money.format(period.expense)}", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { (period.income.toDouble() / maximum).toFloat() }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                LinearProgressIndicator(progress = { (period.expense.toDouble() / maximum).toFloat() }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiary)
            }
        }
        item {
            OutlinedButton(onClick = { exportText = complete.text(); export.launch("家庭账本-${month}-${if (yearly) "年报" else "月报"}.txt") },
                enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("导出报告") }
            OutlinedButton(onClick = { requestAi = true }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()) { Text("AI 解读本期报告") }
            if (state.reportKey == reportKey && state.reportText != null) {
                Text("AI 解读", style = MaterialTheme.typography.titleLarge)
                Text(state.reportText)
                Text("AI 解读基于汇总数据；金额以上方账本统计为准。", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Text("结余为本期收入减支出，不代表账户余额。余额调整不计入本报告。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (requestAi) AlertDialog(onDismissRequest = { requestAi = false }, title = { Text("发送报告汇总给 AI？") },
        text = { Text("将发送本期收支、分类、成员和月度汇总，不发送逐笔备注。请先在设置中连接云端。") },
        confirmButton = { TextButton(onClick = { requestAi = false; model.analyzeReport(complete, reportKey) }) { Text("生成解读") } },
        dismissButton = { TextButton(onClick = { requestAi = false }) { Text("取消") } })
}
