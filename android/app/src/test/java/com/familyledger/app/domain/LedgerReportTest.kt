package com.familyledger.app.domain

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class LedgerReportTest {
    private fun row(date: String, cents: Long, type: EntryType = EntryType.EXPENSE) = LedgerEntry(
        id = "$date-$cents-$type", type = type, occurredOn = date, amountMinor = cents,
        categoryL1 = "日常", account = "银行卡", member = "家人", recordedBy = "操作者", updatedAt = 0
    )

    @Test fun dayIncludesLeapDayAndComparesOnlyPreviousDay() {
        val report = buildReport(listOf(row("2024-02-27", 90), row("2024-02-28", 3),
            row("2024-02-29", 1), row("2024-02-29", 2), row("2024-03-01", 99)),
            LocalDate.parse("2024-02-29"), ReportPeriod.DAY)
        assertEquals("2024-02-29", report.period)
        assertEquals(3L, report.summary.expense)
        assertEquals(3L, report.previousExpense)
        assertEquals(2, report.count)
        assertEquals(7, report.trend.size)
        assertEquals("2024-02-23", report.trend.first().period)
        assertEquals(3L, report.trend.last().expense)
    }

    @Test fun weekIsMondayThroughSundayAcrossYearAndUsesPreviousWeek() {
        val rows = listOf(row("2025-12-21", 999), row("2025-12-22", 3), row("2025-12-28", 5),
            row("2025-12-29", 7), row("2026-01-04", 11), row("2026-01-05", 999))
        val report = buildReport(rows, LocalDate.parse("2026-01-04"), ReportPeriod.WEEK)
        assertEquals("2025-12-29 至 2026-01-04", report.period)
        assertEquals(18L, report.summary.expense)
        assertEquals(8L, report.previousExpense)
        assertEquals(7, report.trend.size)
        assertEquals("2025-12-29", report.trend.first().period)
        assertEquals("2026-01-04", report.trend.last().period)
        assertEquals(report, buildReport(rows, LocalDate.parse("2025-12-29"), ReportPeriod.WEEK))
    }

    @Test fun weekCrossesLeapMonthWithExclusiveEnd() {
        val report = buildReport(listOf(row("2024-02-25", 1), row("2024-02-26", 2),
            row("2024-02-29", 3), row("2024-03-03", 5), row("2024-03-04", 100)),
            LocalDate.parse("2024-02-29"), ReportPeriod.WEEK)
        assertEquals("2024-02-26 至 2024-03-03", report.period)
        assertEquals(10L, report.summary.expense)
        assertEquals(1L, report.previousExpense)
    }

    @Test fun allReportsExcludeAdjustmentsDeletedAndForeignCurrency() {
        val rows = listOf(row("2026-09-06", 1), row("2026-09-06", 2),
            row("2026-09-06", 10, EntryType.INCOME), row("2026-09-06", 999, EntryType.BALANCE_ADJUSTMENT),
            row("2026-09-06", 998).copy(deletedAt = 1), row("2026-09-06", 997).copy(currency = "USD"))
        ReportPeriod.entries.forEach { period ->
            val report = buildReport(rows, LocalDate.parse("2026-09-06"), period)
            assertEquals(3L, report.summary.expense)
            assertEquals(10L, report.summary.income)
            assertEquals(7L, report.summary.balance)
            assertEquals(3, report.count)
            assertEquals(listOf(CategoryTotal("家人", 3)), report.members)
            assertEquals(listOf(CategoryTotal("日常", 3)), report.summary.categories)
        }
    }

    @Test fun oldMonthAndYearApiRetainsTrendAndComparison() {
        val rows = listOf(row("2023-12-31", 2), row("2024-01-01", 3), row("2024-02-29", 5), row("2025-01-01", 99))
        val month = buildReport(rows, YearMonth.of(2024, 1), false)
        assertEquals(3L, month.summary.expense)
        assertEquals(2L, month.previousExpense)
        assertEquals("2023-08", month.trend.first().period)
        assertEquals(6, month.trend.size)
        val year = buildReport(rows, YearMonth.of(2024, 9), true)
        assertEquals(8L, year.summary.expense)
        assertEquals(2L, year.previousExpense)
        assertEquals(12, year.trend.size)
        assertEquals("2024-12", year.trend.last().period)
    }

    @Test fun emptyReportsAndExportHavePeriodAppropriateLabels() {
        ReportPeriod.entries.forEach { period ->
            val report = buildReport(emptyList(), LocalDate.parse("2026-01-01"), period)
            assertEquals(0L, report.summary.balance)
            assertEquals(0, report.count)
            assertTrue(report.text().contains(report.period))
            assertTrue(report.text().contains(report.trendTitle))
            assertTrue(report.text().contains(period.previousLabel))
        }
    }
}
