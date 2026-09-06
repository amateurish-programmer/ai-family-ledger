package com.familyledger.app.data

import com.familyledger.app.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FinanceContextTest {
    private val today = LocalDate.of(2026, 9, 6)
    private fun row(id: String, amount: Long = 10, date: String = "2026-09-06") = LedgerEntry(
        id, EntryType.EXPENSE, date, amount, "食品", account = "私密账户", member = "老婆", recordedBy = "老公", note = "私密备注")
    private fun values(context: JSONObject): Map<String, String> = context.getJSONArray("facts").let { facts ->
        (0 until facts.length()).associate { facts.getJSONObject(it).let { f -> f.getString("label") to f.getString("value") } }
    }
    @Test fun exactTotalsExcludeDeletedForeignAndBalanceAdjustments() {
        val rows = listOf(row("a"), row("b", 20), row("c", 100).copy(type = EntryType.INCOME),
            row("d", 900).copy(deletedAt = 1), row("e", 900).copy(currency = "USD"), row("f", 900).copy(type = EntryType.BALANCE_ADJUSTMENT))
        val facts = values(FinanceContext.build(rows, today, "尚未同步"))
        assertEquals("¥ 0.30", facts["选定范围合计 · 支出"])
        assertEquals("¥ 1.00", facts["选定范围合计 · 收入"])
        assertEquals("¥ 0.70", facts["选定范围合计 · 结余"])
        assertEquals("3 笔", facts["选定范围合计 · 收支笔数"])
    }
    @Test fun selectedQueryDoesNotLeakOtherMemberOrDates() {
        val rows = listOf(row("a"), row("b").copy(member = "老公"), row("c", date = "2026-10-01"))
        val facts = values(FinanceContext.build(rows, today, "无", ChatQuery("2026-09-01", "2026-10-01", "老婆", "食品", "")))
        assertEquals("1 笔", facts["选定范围合计 · 收支笔数"])
        assertEquals("¥ 0.10", facts["选定范围合计 · 支出"])
    }
    @Test fun emptyPeriodsAreExplicitAndDataCoverageIsQualified() {
        val context = FinanceContext.build(emptyList(), today, "尚未成功同步")
        assertEquals("0 笔", values(context)["2026-09 · 收支笔数"])
        assertTrue(context.toString().contains("无记录月份不等于实际零收入"))
        assertTrue(context.toString().contains("尚未成功同步"))
    }
    @Test fun contextContainsNoRawNotesOrAccountNames() {
        val context = FinanceContext.build(listOf(row("a")), today, "无").toString()
        assertFalse(context.contains("私密备注")); assertFalse(context.contains("私密账户"))
        assertTrue(context.contains("成员支出 老婆"))
    }
    @Test fun historyIsBoundedButAllTimeTotalsRemainComplete() {
        val rows = (2000..2026).flatMap { year -> (1..12).map { month -> row("$year-$month", 100, "%04d-%02d-01".format(year, month)) } }
        val context = FinanceContext.build(rows, today, "无")
        assertTrue(context.getJSONArray("facts").length() <= 160)
        assertEquals("¥ 324.00", values(context)["选定范围合计 · 支出"])
        assertEquals("¥ 12.00", values(context)["2026年 · 支出"])
        assertNull(values(context)["2000年 · 支出"])
    }
    @Test fun aggregateOverflowFailsRatherThanWrapping() {
        assertThrows(ArithmeticException::class.java) { FinanceContext.build(listOf(row("a", Long.MAX_VALUE), row("b", 1)), today, "无") }
    }
}
