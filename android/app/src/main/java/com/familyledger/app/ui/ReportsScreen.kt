package com.familyledger.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familyledger.app.domain.*
import java.time.YearMonth
import java.time.LocalDate
import android.app.DatePickerDialog

@Composable fun ReportsScreen(entries: List<LedgerEntry>, month: YearMonth, onMonth: (YearMonth) -> Unit, model: LedgerViewModel, state: LedgerState) {
    var periodName by rememberSaveable { mutableStateOf(ReportPeriod.MONTH.name) }
    var selectedDay by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val period = ReportPeriod.valueOf(periodName)
    val daily = period == ReportPeriod.DAY || period == ReportPeriod.WEEK
    val anchor = if (daily) LocalDate.parse(selectedDay) else month.atDay(1)
    val complete = remember(entries, anchor, period) { buildReport(entries, anchor, period) }
    val report = complete.summary
    val reportKey = "${complete.period}|$period|${entries.hashCode()}"
    val context = LocalContext.current
    var requestAi by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { model.exportReport(context.contentResolver, it, exportText) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            PageHeading("收支报告", "日 · 周 · 月 · 年收支统计") { IconBadge(Icons.Outlined.BarChart, sage = true) }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReportPeriod.entries.forEach { choice ->
                    FilterChip(selected = period == choice, onClick = {
                        if (period != choice && (choice == ReportPeriod.DAY || choice == ReportPeriod.WEEK)) selectedDay = LocalDate.now().toString()
                        periodName = choice.name
                    }, label = { Text(choice.label) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            PeriodSelector(if (period == ReportPeriod.WEEK) complete.period.replace(" 至 ", "\n至 ") else complete.period,
                { val previous = period.move(anchor, -1); if (daily) selectedDay = previous.toString() else onMonth(YearMonth.from(previous)) },
                { val next = period.move(anchor, 1); if (daily) selectedDay = next.toString() else onMonth(YearMonth.from(next)) })
            TextButton(onClick = {
                DatePickerDialog(context, { _, year, monthIndex, day ->
                    val chosen = LocalDate.of(year, monthIndex + 1, day)
                    if (daily) selectedDay = chosen.toString() else onMonth(YearMonth.from(chosen))
                }, anchor.year, anchor.monthValue - 1, anchor.dayOfMonth).show()
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(when (period) {
                    ReportPeriod.DAY -> "选择日期"
                    ReportPeriod.WEEK -> "选择周内任一天"
                    ReportPeriod.MONTH -> "选择日期所在月"
                    ReportPeriod.YEAR -> "选择日期所在年"
                })
            }
        }
        item { SummaryBlock(report) }
        item {
            val difference = Math.subtractExact(report.expense, complete.previousExpense)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (difference > 0) Icons.Outlined.TrendingUp else Icons.Outlined.TrendingDown, null,
                    tint = if (difference > 0) MaterialTheme.colorScheme.primary else LedgerSage)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("支出较${period.previousLabel}${if (difference >= 0) "增加" else "减少"} ¥ ${Money.format(kotlin.math.abs(difference))}", style = MaterialTheme.typography.titleSmall)
                    Text("本期 ${complete.count} 笔收支 · ${period.previousLabel}支出 ¥ ${Money.format(complete.previousExpense)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant) }
        item { SectionHeading("支出去向", "按一级分类统计 · 人民币") }
        if (report.categories.isEmpty()) item { EmptyLedger("还没有支出记录", "确认记账后，支出去向会显示在这里。") }
        items(report.categories, key = { "category:${it.name}" }) { category ->
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(category.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text("¥ ${Money.format(category.amount)}", style = MaterialTheme.typography.titleSmall)
                }
                LinearProgressIndicator(progress = { (category.amount.toDouble() / report.expense.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp), color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer)
            }
        }
        item { HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant) }
        item { SectionHeading("成员支出", "按账目归属成员统计") }
        if (complete.members.isEmpty()) item { Text("本期暂无成员支出。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(complete.members, key = { "member:${it.name}" }) { member ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Outlined.PersonOutline, sage = true)
                Text(member.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text("¥ ${Money.format(member.amount)}", modifier = Modifier.widthIn(max = 165.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
        item { HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant) }
        item {
            SectionHeading(complete.trendTitle)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("━ 收入", color = LedgerSage, style = MaterialTheme.typography.labelMedium)
                Text("━ 支出", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
        }
        items(complete.trend, key = { "trend:${it.period}" }) { period ->
            val maximum = complete.trend.maxOfOrNull { maxOf(it.income, it.expense) }?.coerceAtLeast(1) ?: 1
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(period.period, style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(progress = { (period.income.toDouble() / maximum).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f).height(6.dp), color = LedgerSage, trackColor = MaterialTheme.colorScheme.secondaryContainer)
                    Text(Money.format(period.income), Modifier.widthIn(min = 72.dp, max = 148.dp), style = MaterialTheme.typography.bodySmall, color = LedgerSage)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(progress = { (period.expense.toDouble() / maximum).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f).height(6.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primaryContainer)
                    Text(Money.format(period.expense), Modifier.widthIn(min = 72.dp, max = 148.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item { HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant) }
        item {
            SectionHeading("读懂这份报告", "AI 根据汇总数据解释收支，金额以账本统计为准。")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { requestAi = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("AI 解读本期报告")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { exportText = complete.text(); export.launch("家庭账本-${complete.period}-${period.label}.txt") },
                enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("导出报告") }
            if (state.reportKey == reportKey && state.reportText != null) {
                Surface(Modifier.fillMaxWidth().padding(top = 16.dp).animateContentSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("AI 解读", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(state.reportText, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
        }
        item { Text("结余为本期收入减支出，不代表账户余额。余额调整不计入本报告。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (requestAi) AlertDialog(onDismissRequest = { requestAi = false }, title = { Text("发送报告汇总给 AI？") },
        text = { Text("将发送本期收支、分类、成员和${if (daily) "每日" else "月度"}趋势汇总，不发送逐笔备注。请先在设置中连接云端。") },
        confirmButton = { TextButton(onClick = { requestAi = false; model.analyzeReport(complete, reportKey) }) { Text("生成解读") } },
        dismissButton = { TextButton(onClick = { requestAi = false }) { Text("取消") } })
}
