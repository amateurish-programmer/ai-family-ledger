package com.familyledger.app.domain

import java.time.YearMonth

data class PeriodTotal(val period: String, val income: Long, val expense: Long)
data class LedgerReport(val period: String, val summary: Summary, val previousExpense: Long,
    val members: List<CategoryTotal>, val trend: List<PeriodTotal>, val count: Int) {
    fun text(): String = buildString {
        appendLine("家庭账本 · $period")
        appendLine("收入：¥ ${Money.format(summary.income)}")
        appendLine("支出：¥ ${Money.format(summary.expense)}")
        appendLine("结余：¥ ${Money.format(summary.balance)}")
        appendLine("有效收支：$count 笔")
        appendLine("上期支出：¥ ${Money.format(previousExpense)}")
        appendLine("\n支出分类")
        summary.categories.forEach { appendLine("${it.name}：¥ ${Money.format(it.amount)}") }
        appendLine("\n成员支出")
        members.forEach { appendLine("${it.name}：¥ ${Money.format(it.amount)}") }
        appendLine("\n月度趋势（收入 / 支出）")
        trend.forEach { appendLine("${it.period}：¥ ${Money.format(it.income)} / ¥ ${Money.format(it.expense)}") }
        appendLine("\n结余不代表账户余额；余额变更不计收支。")
    }
}
fun buildReport(entries: List<LedgerEntry>, month: YearMonth, yearly: Boolean): LedgerReport {
    val start = if (yearly) month.atDay(1).withDayOfYear(1) else month.atDay(1)
    val end = if (yearly) start.plusYears(1) else start.plusMonths(1)
    val previous = if (yearly) start.minusYears(1) else start.minusMonths(1)
    val rows = entries.filter { it.deletedAt == null && it.currency == "CNY" && it.occurredOn >= start.toString() && it.occurredOn < end.toString() }
    val firstMonth = if (yearly) YearMonth.of(month.year, 1) else month.minusMonths(5)
    val trend = (0 until if (yearly) 12 else 6).map { offset ->
        val m = firstMonth.plusMonths(offset.toLong())
        val s = summarize(entries, m.atDay(1), m.plusMonths(1).atDay(1))
        PeriodTotal(m.toString(), s.income, s.expense)
    }
    return LedgerReport(if (yearly) "${month.year} 年" else "${month.year} 年 ${month.monthValue} 月",
        summarize(entries, start, end), summarize(entries, previous, start).expense,
        rows.filter { it.type == EntryType.EXPENSE }.groupBy { it.member }.map { (name, list) ->
            CategoryTotal(name, list.fold(0L) { sum, e -> Math.addExact(sum, e.amountMinor) })
        }.sortedByDescending { it.amount }, trend, rows.count { it.type != EntryType.BALANCE_ADJUSTMENT })
}
