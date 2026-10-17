package com.familyledger.app.ui

import android.app.DatePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.familyledger.app.domain.*
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun ReportsScreen(entries: List<LedgerEntry>, month: YearMonth, onMonth: (YearMonth) -> Unit,
    model: LedgerViewModel, state: LedgerState, onOrganizeGifts: () -> Unit = {}) {
    val context = LocalContext.current
    var exportText by rememberSaveable { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { model.exportReport(context.contentResolver, it, exportText) }
        model.finishDocumentPicker(cancelled = uri == null)
    }
    ReportContent(entries, month, onMonth, state.syncing, state.busy, state.reportKey, state.reportText,
        onAnalyze = { report, key -> model.analyzeReport(report, key) },
        onExport = { report ->
            exportText = report.text()
            model.launchDocumentPicker { export.launch("家庭账本-${report.period}-${report.kind.label}.txt") }
        }, onOrganizeGifts = onOrganizeGifts)
}

private enum class ReportSection(val label: String, val tag: String) {
    OVERVIEW("总览", "overview"), CATEGORIES("分类", "categories"), MEMBERS("成员", "members"), GIFTS("人情", "gifts")
}

/** Sections share one report range; scroll and gift history scope are saved independently. */
@Composable
internal fun ReportContent(entries: List<LedgerEntry>, month: YearMonth, onMonth: (YearMonth) -> Unit,
    syncing: Boolean, busy: Boolean, aiReportKey: String?, aiReportText: String?,
    onAnalyze: (LedgerReport, String) -> Unit, onExport: (LedgerReport) -> Unit, onOrganizeGifts: () -> Unit) {
    var periodName by rememberSaveable { mutableStateOf(ReportPeriod.MONTH.name) }
    var selectedDay by rememberSaveable {
        mutableStateOf((if (month == YearMonth.now()) LocalDate.now() else month.atDay(1)).toString())
    }
    var sectionName by rememberSaveable { mutableStateOf(ReportSection.OVERVIEW.name) }
    val period = ReportPeriod.valueOf(periodName)
    val section = ReportSection.valueOf(sectionName)
    val daily = period == ReportPeriod.DAY || period == ReportPeriod.WEEK
    val anchor = if (daily) LocalDate.parse(selectedDay) else month.atDay(1)
    val complete = remember(entries, anchor, period) { buildReport(entries, anchor, period) }
    val report = complete.summary
    val reportKey = "${complete.period}|$period|${entries.hashCode()}"
    val context = LocalContext.current
    val style = LocalLedgerStyle.current
    val sectionStates = rememberSaveableStateHolder()
    var requestAi by remember { mutableStateOf(false) }
    fun chooseDate(date: LocalDate) {
        selectedDay = date.toString()
        onMonth(YearMonth.from(date))
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().zIndex(1f).background(MaterialTheme.colorScheme.background)
            .padding(horizontal = style.pagePadding).padding(top = 8.dp, bottom = 4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("收支报告", modifier = Modifier.testTag("report_heading"), style = MaterialTheme.typography.titleLarge)
                if (syncing) SyncStatus(true)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Outlined.BarChart, null, Modifier.size(22.dp), tint = LedgerSage)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().testTag("report_period_choices"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReportPeriod.entries.forEach { choice ->
                    FilterChip(selected = period == choice, onClick = {
                        if (daily != (choice == ReportPeriod.DAY || choice == ReportPeriod.WEEK)) {
                            val rememberedDay = LocalDate.parse(selectedDay).dayOfMonth.coerceAtMost(month.lengthOfMonth())
                            chooseDate(month.atDay(rememberedDay))
                        }
                        periodName = choice.name
                    }, label = { Text(choice.label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { chooseDate(period.move(anchor, -1)) }, modifier = Modifier.testTag("report_period_previous")) {
                    Icon(Icons.Outlined.ChevronLeft, "上一期")
                }
                TextButton(onClick = {
                    DatePickerDialog(context, { _, year, monthIndex, day -> chooseDate(LocalDate.of(year, monthIndex + 1, day)) },
                        anchor.year, anchor.monthValue - 1, anchor.dayOfMonth).show()
                }, modifier = Modifier.weight(1f).testTag("report_period_label"), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                    Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(complete.period, textAlign = TextAlign.Center,
                        style = if (period == ReportPeriod.WEEK) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall)
                }
                IconButton(onClick = { chooseDate(period.move(anchor, 1)) }, modifier = Modifier.testTag("report_period_next")) {
                    Icon(Icons.Outlined.ChevronRight, "下一期")
                }
            }
            Row(Modifier.fillMaxWidth()) {
                ReportSection.entries.forEach { choice ->
                    val selected = section == choice
                    Box(Modifier.weight(1f).selectable(selected = selected, role = Role.Tab, onClick = { sectionName = choice.name })
                        .heightIn(min = 48.dp).testTag("report_section_${choice.tag}"), contentAlignment = Alignment.Center) {
                        Text(choice.label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge)
                        if (selected) Box(Modifier.align(Alignment.BottomCenter).width(32.dp).height(2.dp)
                            .background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
        sectionStates.SaveableStateProvider(section.name) {
            val listState = rememberLazyListState()
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("report_list"), state = listState,
                contentPadding = PaddingValues(start = style.pagePadding, end = style.pagePadding, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(style.sectionSpacing)) {
                when (section) {
                    ReportSection.OVERVIEW -> {
                        item(key = "summary") { CompactReportSummary(report) }
                        item(key = "comparison") {
                            val difference = Math.subtractExact(report.expense, complete.previousExpense)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (difference > 0) Icons.Outlined.TrendingUp else Icons.Outlined.TrendingDown, null,
                                    tint = if (difference > 0) MaterialTheme.colorScheme.primary else LedgerSage, modifier = Modifier.size(20.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("支出较${period.previousLabel}${if (difference >= 0) "增加" else "减少"} ¥ ${Money.format(kotlin.math.abs(difference))}",
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text("本期 ${complete.count} 笔收支", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (!daily) item(key = "monthly_trend") { LedgerPanel { MonthlyTrendChart(entries, YearMonth.from(anchor)) } }
                        else item(key = "daily_trend") { DailyReportTrend(complete) }
                        item(key = "analysis") {
                            LedgerPanel(accent = true) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text("读懂这份报告", style = MaterialTheme.typography.titleMedium)
                                }
                                Text("看看钱花在了哪里，结合消费明细给出收支建议。", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(onClick = { requestAi = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Text("AI 解读本期报告")
                                }
                                if (aiReportKey == reportKey && aiReportText != null) {
                                    AssistantMessage(aiReportText, Modifier.fillMaxWidth().animateContentSize())
                                }
                            }
                        }
                        item(key = "export") {
                            OutlinedButton(onClick = { onExport(complete) }, enabled = !busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp)); Text("导出报告")
                            }
                        }
                        item { Text("结余为收入减支出，不代表账户余额；余额调整不计入报告。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    ReportSection.CATEGORIES -> {
                        item { SectionHeading("支出去向", "按一级分类统计 · 本期支出 ¥ ${Money.format(report.expense)}") }
                        if (report.categories.isEmpty()) item { EmptyLedger("还没有支出记录", "确认记账后，支出去向会显示在这里。") }
                        itemsIndexed(report.categories, key = { _, row -> "category:${row.name}" }) { index, category ->
                            LedgerPanel(contentPadding = style.rowPadding) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${index + 1}".padStart(2, '0'), color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelMedium)
                                    RankedAmount(category.name, "¥ ${Money.format(category.amount)}", Modifier.weight(1f))
                                }
                                LinearProgressIndicator(progress = { (category.amount.toDouble() / report.expense.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(5.dp), color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.primaryContainer)
                            }
                        }
                    }
                    ReportSection.MEMBERS -> {
                        item { SectionHeading("成员支出", "按账目归属统计，记账操作者单独记录。") }
                        if (complete.members.isEmpty()) item { EmptyLedger("本期暂无成员支出", "已入账的支出会按归属成员汇总。") }
                        itemsIndexed(complete.members, key = { _, row -> "member:${row.name}" }) { _, member ->
                            LedgerPanel(contentPadding = style.rowPadding) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    IconBadge(Icons.Outlined.PersonOutline, sage = true)
                                    RankedAmount(member.name, "¥ ${Money.format(member.amount)}", Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    ReportSection.GIFTS -> item(key = "gift_report") { GiftReportSection(entries, period.range(anchor), onOrganizeGifts) }
                }
            }
        }
    }
    if (requestAi) AlertDialog(onDismissRequest = { requestAi = false }, title = { Text("解读本期消费明细？") },
        text = { Text("将发送本期及对照期间统计，以及本期最多 40 笔收支的日期、金额、分类、成员、商家和备注节选，用于分析消费并给出建议。记录较多时优先大额收支，再补最近记录；不发送账户字段。") },
        confirmButton = { TextButton(onClick = { requestAi = false; onAnalyze(complete, reportKey) }) { Text("生成解读") } },
        dismissButton = { TextButton(onClick = { requestAi = false }) { Text("取消") } })
}

@Composable
private fun CompactReportSummary(summary: Summary) {
    val style = LocalLedgerStyle.current
    val largeType = LocalDensity.current.fontScale >= 1.3f
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (largeType) {
            ReportAmount("收入", summary.income, LedgerSage)
            ReportAmount("支出", summary.expense, MaterialTheme.colorScheme.primary)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ReportAmount("收入", summary.income, LedgerSage, Modifier.weight(1f))
                ReportAmount("支出", summary.expense, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("本期收支结余", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("¥ ${Money.format(summary.balance)}", Modifier.weight(1f), textAlign = TextAlign.End,
                style = MaterialTheme.typography.titleSmall, fontFamily = style.moneyFontFamily)
        }
    }
}

@Composable private fun ReportAmount(label: String, amount: Long, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("¥ ${Money.format(amount)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
            fontFamily = LocalLedgerStyle.current.moneyFontFamily, color = color)
    }
}

@Composable
private fun DailyReportTrend(report: LedgerReport) {
    val maximum = report.trend.maxOfOrNull { maxOf(it.income, it.expense) }?.coerceAtLeast(1) ?: 1
    LedgerPanel {
        SectionHeading(report.trendTitle)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("━ 收入", color = LedgerSage, style = MaterialTheme.typography.labelMedium)
            Text("━ 支出", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        }
        report.trend.forEach { day ->
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(day.period, style = MaterialTheme.typography.labelMedium)
                listOf(true, false).forEach { income ->
                    val amount = if (income) day.income else day.expense
                    val color = if (income) LedgerSage else MaterialTheme.colorScheme.primary
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinearProgressIndicator(progress = { (amount.toDouble() / maximum).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.weight(1f).height(5.dp), color = color, trackColor = color.copy(alpha = .12f))
                        Text(Money.format(amount), Modifier.widthIn(min = 72.dp, max = 148.dp),
                            style = MaterialTheme.typography.bodySmall, fontFamily = LocalLedgerStyle.current.moneyFontFamily, color = color)
                    }
                }
            }
        }
    }
}
