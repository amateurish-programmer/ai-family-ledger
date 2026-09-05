package com.familyledger.app.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class LedgerRulesTest {
    @Test fun moneyIsExactToTheCent() {
        assertEquals(6380L, Money.parse("63.80"))
        assertEquals(1L, Money.parse("0.01"))
        assertEquals(300L, Money.parse(" 3 "))
        assertEquals("63.80", Money.format(6380))
        assertEquals("-0.01", Money.format(-1))
    }

    @Test fun rejectsInvalidOrAmbiguousAmounts() {
        listOf("", "0", "-3", "1.001", "1e3", "1,000", "NaN", "999999999999999999").forEach {
            assertThrows(IllegalArgumentException::class.java) { Money.parse(it) }
        }
    }

    @Test fun reportsUseExclusiveEndAndIgnoreDeletedAndAdjustments() {
        val rows = listOf(
            row("2026-08-01", EntryType.INCOME, 10000),
            row("2026-08-31", EntryType.EXPENSE, 1010),
            row("2026-09-01", EntryType.EXPENSE, 9900),
            row("2026-07-31", EntryType.EXPENSE, 500),
            row("2026-08-05", EntryType.BALANCE_ADJUSTMENT, 500000),
            row("2026-08-10", EntryType.EXPENSE, 300).copy(deletedAt = 1)
        )
        val result = summarize(rows, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-09-01"))
        assertEquals(10000L, result.income)
        assertEquals(1010L, result.expense)
        assertEquals(8990L, result.balance)
        assertEquals(listOf(CategoryTotal("食品酒水", 1010)), result.categories)
    }

    @Test fun yearlyReportIncludesLeapDayAndExcludesNextYear() {
        val rows = listOf(row("2024-02-29", EntryType.EXPENSE, 10), row("2025-01-01", EntryType.EXPENSE, 20))
        assertEquals(10L, summarize(rows, LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1)).expense)
    }

    @Test fun invalidCalendarDateIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { validateDate("2026-02-30") }
        assertEquals("2024-02-29", validateDate("2024-02-29"))
    }

    private fun row(date: String, type: EntryType, cents: Long) = LedgerEntry(
        id = "id-$date-$type", type = type, occurredOn = date, amountMinor = cents,
        categoryL1 = "食品酒水", account = "银行卡", member = "本人", recordedBy = "本人"
    )
}
