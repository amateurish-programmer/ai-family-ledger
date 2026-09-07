package com.familyledger.app.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class GiftReportTest {
    private fun row(id: String, name: String = "对象甲", amount: Long = 1, type: EntryType = EntryType.EXPENSE,
        date: String = "2026-09-07") = LedgerEntry(id = id, type = type, occurredOn = date, amountMinor = amount,
        categoryL1 = "人情", account = "现金", member = "成员", recordedBy = "操作者", isGift = true, counterparty = name)

    @Test fun totalsAreExactAndPendingCountsButDoesNotRank() {
        val report = buildGiftReport(listOf(row("1", amount = 1), row("2", amount = 2),
            row("3", "  ", 7), row("4", amount = 15, type = EntryType.INCOME)))
        assertEquals(15L, report.income)
        assertEquals(10L, report.expense)
        assertEquals(5L, report.difference)
        assertEquals(listOf(CategoryTotal("对象甲", 3)), report.expenseRanking)
        assertEquals(listOf("3"), report.pendingEntries.map { it.id })
        assertEquals(listOf("3"), report.forCounterparty("").map { it.id })
    }

    @Test fun onlyTrimMatchingNamesAndDeterministicTies() {
        val report = buildGiftReport(listOf(row("1", " Alice ", 2), row("2", "Alice", 3),
            row("3", "alice", 5), row("4", "Alice 家", 8), row("5", "Alice家", 1)))
        assertEquals(listOf("Alice 家", "Alice", "alice", "Alice家"), report.expenseRanking.map { it.name })
        assertEquals(2, report.forCounterparty(" Alice ").size)
        assertEquals(report.expenseRanking, buildGiftReport(report.entries.reversed()).expenseRanking)
    }

    @Test fun excludesUnconfirmedDeletedCurrencyAndAdjustments() {
        val report = buildGiftReport(listOf(row("1"), row("2").copy(isGift = false),
            row("3").copy(deletedAt = 1), row("4").copy(currency = "USD"),
            row("5", type = EntryType.BALANCE_ADJUSTMENT)))
        assertEquals(listOf("1"), report.entries.map { it.id })
        assertEquals(1L, report.expense)
    }

    @Test fun allPeriodsRespectExclusiveBoundsAndAllHistory() {
        val anchor = LocalDate.parse("2024-02-29")
        ReportPeriod.entries.forEach { period ->
            val range = period.range(anchor)
            val rows = listOf(row("before", date = range.start.minusDays(1).toString()),
                row("start", date = range.start.toString()), row("last", date = range.endExclusive.minusDays(1).toString()),
                row("end", date = range.endExclusive.toString()))
            assertEquals(2L, buildGiftReport(rows, range).expense)
            assertEquals(4L, buildGiftReport(rows).expense)
        }
        val crossYear = ReportPeriod.WEEK.range(LocalDate.parse("2026-01-04"))
        assertEquals(2L, buildGiftReport(listOf(row("a", date = "2025-12-29"), row("b", date = "2026-01-04"),
            row("c", date = "2026-01-05")), crossYear).expense)
    }

    @Test fun largeAmountsRetainCentPrecision() {
        val amount = 9_007_199_254_740_993L
        assertEquals(amount + 1, buildGiftReport(listOf(row("1", amount = amount), row("2"))).expense)
        assertEquals(-amount - 1, buildGiftReport(listOf(row("1", amount = amount), row("2"))).difference)
    }

    @Test(expected = ArithmeticException::class) fun rejectsOverflowInsteadOfWrapping() {
        buildGiftReport(listOf(row("1", amount = Long.MAX_VALUE), row("2")))
    }

    @Test fun emptyReportHasZeroTotalsAndNoRankings() {
        val report = buildGiftReport(emptyList())
        assertEquals(0L, report.difference)
        assertTrue(report.incomeRanking.isEmpty())
        assertTrue(report.expenseRanking.isEmpty())
    }
}
