package com.familyledger.app.data

import com.familyledger.app.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ReportAnalysisContextTest {
    private val today = LocalDate.parse("2026-10-17")
    private fun row(id: String, cents: Long = 13, date: String = "2026-10-17") = LedgerEntry(
        id, EntryType.EXPENSE, date, cents, "食品", "午餐", "不应发送的账户", "家人", "不应发送的操作者",
        merchant = "便餐店", note = "工作日午餐")
    private fun build(rows: List<LedgerEntry>, date: LocalDate = today, period: ReportPeriod = ReportPeriod.MONTH) =
        ReportAnalysisContext.build(rows, date, period, today, "尚未同步")
    private fun facts(input: JSONObject) = input.getJSONObject("finance").getJSONArray("facts").let { array ->
        (0 until array.length()).associate { array.getJSONObject(it).let { f -> f.getString("label") to f.getString("value") } }
    }

    @Test fun exactTotalsAndDetailsExcludeForeignDeletedAndAdjustments() {
        val input = build(listOf(row("a", 1), row("b", 2), row("income", 10).copy(type = EntryType.INCOME),
            row("deleted", 999).copy(deletedAt = 1), row("foreign", 999).copy(currency = "USD"),
            row("adjust", 999).copy(type = EntryType.BALANCE_ADJUSTMENT), row("outside", 999, "2026-11-01")))
        assertTrue(input.getBoolean("reportV2"))
        val facts = facts(input)
        assertEquals("¥ 0.03", facts["本期支出"])
        assertEquals("¥ 0.10", facts["本期收入"])
        assertEquals("¥ 0.07", facts["本期结余"])
        assertEquals("3 笔", facts["本期收支笔数"])
        assertEquals(3, facts.keys.count { it.startsWith("本期明细 ") })
        assertTrue(input.toString().contains("工作日午餐"))
        assertFalse(input.toString().contains("不应发送"))
    }
    @Test fun dailyWeeklyAndYearlyBoundsUseTheSelectedRange() {
        val rows = listOf(row("a", 1, "2025-12-28"), row("b", 2, "2025-12-29"),
            row("c", 3, "2026-01-04"), row("d", 4, "2026-01-05"))
        val anchor = LocalDate.parse("2026-01-04")
        assertEquals("¥ 0.03", facts(build(rows, anchor, ReportPeriod.DAY))["本期支出"])
        assertEquals("¥ 0.05", facts(build(rows, anchor, ReportPeriod.WEEK))["本期支出"])
        assertEquals("¥ 0.07", facts(build(rows, anchor, ReportPeriod.YEAR))["本期支出"])
    }
    @Test fun ranksUseAllRowsAndDetailSelectionIncludesLargeAndRecentExpenses() {
        val rows = (1..100).map { row("r$it", 1, "2026-10-17").copy(categoryL2 = "早餐") } +
            row("largest", 10000, "2026-10-01").copy(categoryL2 = "家庭聚餐", note = "月初家庭聚餐")
        val input = build(rows)
        val facts = facts(input)
        assertEquals("¥ 101.00", facts["本期支出"])
        assertTrue(facts["支出细类 食品 / 早餐"]!!.contains("100 笔"))
        assertEquals(40, facts.keys.count { it.startsWith("本期明细 ") })
        assertTrue(facts["明细覆盖"]!!.contains("101"))
        assertTrue(facts.values.any { it.contains("月初家庭聚餐") })
        assertEquals(input.toString(), build(rows.reversed()).toString())
    }
    @Test fun precisionAndZeroBaseDoNotProduceInventedRates() {
        val facts = facts(build(listOf(row("a", 1), row("b", 2).copy(categoryL1 = "出行"))))
        assertEquals("¥ 0.01；33.33%；1 笔", facts["支出分类 食品"])
        assertEquals("¥ 0.02；66.67%；1 笔", facts["支出分类 出行"])
        val empty = build(emptyList()).toString()
        assertFalse(empty.contains("NaN")); assertFalse(empty.contains("Infinity"))
        assertTrue(empty.contains("没有记录不等于实际没有收支"))
    }
    @Test fun longNamesAndNotesStayWithinWireBudgetWithoutChangingTotals() {
        val rows = (1..120).map { row("$it", 1).copy(categoryL1 = "$it" + "分类".repeat(48),
            categoryL2 = "细类".repeat(50), member = "家人".repeat(50), merchant = "商家".repeat(50), note = "🙂".repeat(1000)) }
        val input = build(rows)
        assertTrue(JSONObject().put("operation", "report").put("input", input).toString().toByteArray(Charsets.UTF_8).size < 65536)
        val array = input.getJSONObject("finance").getJSONArray("facts")
        assertTrue(array.length() <= 160)
        (0 until array.length()).forEach { i ->
            assertTrue(array.getJSONObject(i).getString("label").length <= 100)
            assertTrue(array.getJSONObject(i).getString("value").length <= 500)
        }
        assertEquals("¥ 1.20", facts(input)["本期支出"])
    }
    @Test fun incompleteAndFuturePeriodsAreExplicit() {
        assertTrue(facts(build(emptyList()))["期间完整性"]!!.contains("尚未结束"))
        assertTrue(facts(build(emptyList(), today.plusMonths(1)))["期间完整性"]!!.contains("尚未开始"))
        assertTrue(facts(build(emptyList(), today.minusMonths(1)))["期间完整性"]!!.contains("可能缺记"))
    }
    @Test fun overflowFailsWithoutWrapping() {
        assertThrows(ArithmeticException::class.java) { build(listOf(row("a", Long.MAX_VALUE), row("b", 1))) }
    }
}
