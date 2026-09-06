package com.familyledger.app.domain

import java.time.YearMonth
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

enum class ReportPeriod(val label: String, val previousLabel: String) {
    DAY("日报", "上一日"), WEEK("周报", "上一周"), MONTH("月报", "上月"), YEAR("年报", "上年");

    fun move(date: LocalDate, steps: Long): LocalDate = when (this) {
        DAY -> date.plusDays(steps)
        WEEK -> date.plusWeeks(steps)
        MONTH -> date.plusMonths(steps)
        YEAR -> date.plusYears(steps)
    }

    fun range(date: LocalDate): ReportRange {
        val start = when (this) {
            DAY -> date
            WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            MONTH -> date.withDayOfMonth(1)
            YEAR -> date.withDayOfYear(1)
        }
        return ReportRange(start, move(start, 1), move(start, -1))
    }
}

data class ReportRange(val start: LocalDate, val endExclusive: LocalDate, val previousStart: LocalDate)

data class PeriodTotal(val period: String, val income: Long, val expense: Long)
data class LedgerReport(val period: String, val summary: Summary, val previousExpense: Long,
    val members: List<CategoryTotal>, val trend: List<PeriodTotal>, val count: Int,
    val kind: ReportPeriod = ReportPeriod.MONTH) {
    val trendTitle: String get() = when (kind) {
        ReportPeriod.DAY -> "近七日趋势（含所选日）"
        ReportPeriod.WEEK -> "本周每日趋势"
        ReportPeriod.MONTH -> "近六个月趋势"
        ReportPeriod.YEAR -> "全年月度趋势"
    }
    fun text(): String = buildString {
        appendLine("家庭账本 · $period")
        appendLine("收入：¥ ${Money.format(summary.income)}")
        appendLine("支出：¥ ${Money.format(summary.expense)}")
        appendLine("结余：¥ ${Money.format(summary.balance)}")
        appendLine("有效收支：$count 笔")
        appendLine("${kind.previousLabel}支出：¥ ${Money.format(previousExpense)}")
        appendLine("\n支出分类")
        summary.categories.forEach { appendLine("${it.name}：¥ ${Money.format(it.amount)}") }
        appendLine("\n成员支出")
        members.forEach { appendLine("${it.name}：¥ ${Money.format(it.amount)}") }
        appendLine("\n$trendTitle（收入 / 支出）")
        trend.forEach { appendLine("${it.period}：¥ ${Money.format(it.income)} / ¥ ${Money.format(it.expense)}") }
        appendLine("\n结余不代表账户余额；余额变更不计收支。")
    }
}
fun buildReport(entries: List<LedgerEntry>, month: YearMonth, yearly: Boolean): LedgerReport =
    buildReport(entries, month.atDay(1), if (yearly) ReportPeriod.YEAR else ReportPeriod.MONTH)

fun buildReport(entries: List<LedgerEntry>, date: LocalDate, period: ReportPeriod): LedgerReport {
    val (start, end, previous) = period.range(date)
    val rows = entries.filter { it.deletedAt == null && it.currency == "CNY" && it.occurredOn >= start.toString() && it.occurredOn < end.toString() }
    val daily = period == ReportPeriod.DAY || period == ReportPeriod.WEEK
    val firstMonth = if (period == ReportPeriod.YEAR) YearMonth.of(date.year, 1) else YearMonth.from(date).minusMonths(5)
    val firstDay = if (period == ReportPeriod.DAY) start.minusDays(6) else start
    val trend = (0 until if (daily) 7 else if (period == ReportPeriod.YEAR) 12 else 6).map { offset ->
        val m = firstMonth.plusMonths(offset.toLong())
        val d = firstDay.plusDays(offset.toLong())
        val s = if (daily) summarize(entries, d, d.plusDays(1)) else summarize(entries, m.atDay(1), m.plusMonths(1).atDay(1))
        PeriodTotal(if (daily) d.toString() else m.toString(), s.income, s.expense)
    }
    val label = when (period) {
        ReportPeriod.DAY -> start.toString()
        ReportPeriod.WEEK -> "$start 至 ${end.minusDays(1)}"
        ReportPeriod.MONTH -> "${date.year} 年 ${date.monthValue} 月"
        ReportPeriod.YEAR -> "${date.year} 年"
    }
    return LedgerReport(label,
        summarize(entries, start, end), summarize(entries, previous, start).expense,
        rows.filter { it.type == EntryType.EXPENSE }.groupBy { it.member }.map { (name, list) ->
            CategoryTotal(name, list.fold(0L) { sum, e -> Math.addExact(sum, e.amountMinor) })
        }.sortedByDescending { it.amount }, trend, rows.count { it.type != EntryType.BALANCE_ADJUSTMENT }, period)
}
