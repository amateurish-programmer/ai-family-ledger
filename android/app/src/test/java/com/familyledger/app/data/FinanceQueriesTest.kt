package com.familyledger.app.data

import com.familyledger.app.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FinanceQueriesTest {
    private val today = LocalDate.of(2026, 10, 17)
    private fun row(id: String, amount: Long, date: String = "2026-09-02") = LedgerEntry(id,
        EntryType.EXPENSE, date, amount, "食品", account = "秘密账户", member = "老婆", recordedBy = "老公", note = "秘密备注")
    private fun build(rows: List<LedgerEntry>, queries: List<ChatQuery>) = FinanceContext.buildQueries(rows, today, "尚未同步", queries)
    private fun facts(context: JSONObject) = context.getJSONArray("facts").let { a ->
        (0 until a.length()).associate { a.getJSONObject(it).getString("label") to a.getJSONObject(it).getString("value") }
    }
    @Test fun scopedGroupsUseExactCentsAndProgramComputedComparison() {
        val qs = listOf(ChatQuery("2026-09-01", "2026-10-01", "老婆", "", ""), ChatQuery("2026-10-01", "2026-11-01", "老婆", "", ""))
        val rows = listOf(row("a", 10), row("b", 20), row("c", 40, "2026-10-01"),
            row("foreign", 999).copy(currency = "USD"), row("deleted", 999).copy(deletedAt = 1),
            row("adjust", 999).copy(type = EntryType.BALANCE_ADJUSTMENT), row("other", 999).copy(member = "老公"))
        val result = build(rows, qs)
        val values = facts(result)
        assertEquals("¥ 0.30", values["Q1 · 支出"])
        assertEquals("¥ 0.40", values["Q2 · 支出"])
        assertEquals("¥ 0.10", values["Q2 − Q1 · 支出差额"])
        assertEquals("33.33%", values["Q2 相对 Q1 · 支出变化率"])
        assertTrue(values["Q2 · 期间完整性"]!!.contains("尚未结束"))
        assertTrue(result.toString().contains("未同步"))
        assertFalse(result.toString().contains("秘密账户"))
        assertFalse(result.toString().contains("秘密备注"))
    }
    @Test fun sparsePastAndEmptyPeriodsDoNotClaimCompleteRecordsOrDivideByZero() {
        val qs = listOf(ChatQuery("2026-08-01", "2026-09-01", "", "", ""), ChatQuery("2026-09-01", "2026-10-01", "", "", ""))
        val values = facts(build(listOf(row("a", 30)), qs))
        assertEquals("0 笔", values["Q1 · 收支笔数"])
        assertTrue(values["Q1 · 期间完整性"]!!.contains("不代表记录完整"))
        assertTrue(values["Q2 相对 Q1 · 支出变化率"]!!.contains("无法计算"))
    }
    @Test fun queryFactsAreBoundedWithoutDroppingAggregateRows() {
        val rows = (1..300).map { row("r$it", 10).copy(categoryL1 = "类别$it", merchant = "商家$it", member = "成员$it") }
        val q = ChatQuery("2026-09-01", "2026-10-01", "", "", "")
        val result = build(rows, List(4) { q })
        assertTrue(result.getJSONArray("facts").length() <= 160)
        assertEquals("¥ 30.00", facts(result)["Q4 · 支出"])
        assertFalse(result.toString().contains("秘密备注"))
    }
    @Test fun currentDayIsStillIncompleteWhenEndIsTomorrow() {
        val q = ChatQuery("2026-10-01", "2026-10-18", "", "", "")
        assertTrue(facts(build(emptyList(), listOf(q)))["Q1 · 期间完整性"]!!.contains("尚未结束"))
    }
    @Test fun giftCounterpartyDirectionAndMemberFiltersUseConfirmedMetadataOnly() {
        val gift = row("gift", 150).copy(type = EntryType.INCOME, isGift = true, counterparty = "张三")
        val rows = listOf(gift, gift.copy(id = "two", amountMinor = 50), gift.copy(id = "out", type = EntryType.EXPENSE),
            gift.copy(id = "other", member = "老公"), gift.copy(id = "name", counterparty = "李四"),
            gift.copy(id = "unconfirmed", isGift = false, counterparty = "", note = "张三红包"))
        val q = ChatQuery("2026-09-01", "2026-10-01", "老婆", "", "", label = "礼金", type = "INCOME", giftOnly = true, counterparty = "张三")
        val result = facts(build(rows, listOf(q)))
        assertEquals("2 笔", result["Q1 · 收支笔数"])
        assertEquals("¥ 2.00", result["Q1 · 礼金收入"])
        assertEquals("¥ 0.00", result["Q1 · 支出"])
        assertEquals("¥ 2.00", result["Q1 · 礼金收入往来人 张三"])
    }
    @Test fun detailsAreOptInBoundedAndDoNotIncludeAccountsOrNotes() {
        val rows = (1..15).map { row("r$it", 10, "2026-09-%02d".format(it)).copy(merchant = "店铺$it") }
        val q = ChatQuery("2026-09-01", "2026-10-01", "", "", "")
        assertFalse(facts(build(rows, listOf(q))).keys.any { "明细" in it })
        val result = build(rows, listOf(q.copy(label = "最近交易明细")))
        val values = facts(result)
        assertEquals(5, values.keys.count { it.matches(Regex("Q1 · 明细 [0-9]+")) })
        assertTrue(values["Q1 · 明细 1"]!!.contains("2026-09-15"))
        assertTrue(values["Q1 · 明细范围"]!!.contains("共 15 笔"))
        assertFalse(result.toString().contains("秘密账户"))
        assertFalse(result.toString().contains("秘密备注"))
    }
    @Test fun valuesBeyondDoublePrecisionAndOverflowAreHandledExactly() {
        val qs = listOf(ChatQuery("2026-09-01", "2026-10-01", "", "", ""), ChatQuery("2026-10-01", "2026-11-01", "", "", ""))
        val values = facts(build(listOf(row("a", 9_007_199_254_740_993L), row("b", 9_007_199_254_740_994L, "2026-10-01")), qs))
        assertEquals("¥ 90071992547409.93", values["Q1 · 支出"])
        assertEquals("¥ 0.01", values["Q2 − Q1 · 支出差额"])
        assertThrows(ArithmeticException::class.java) { build(listOf(row("a", Long.MAX_VALUE), row("b", 1)), qs.take(1)) }
    }
    @Test fun longestValidFiltersRemainCompleteWithinFactLimits() {
        val q = ChatQuery("2026-09-01", "2026-10-01", "甲".repeat(100), "乙".repeat(100), "丙".repeat(100),
            "明细" + "标".repeat(98), "EXPENSE", true, "丁".repeat(100))
        val result = build(emptyList(), List(4) { q })
        val values = facts(result)
        listOf(q.member, q.category, q.keyword, q.counterparty).forEach { assertTrue(values["Q4 · 筛选范围"]!!.contains(it)) }
        assertTrue(values.values.all { it.length <= 500 })
        assertTrue(values.keys.all { it.length <= 100 })
        assertTrue(result.getJSONArray("facts").length() <= 160)
    }
}
