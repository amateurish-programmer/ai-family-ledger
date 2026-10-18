package com.familyledger.app.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ReportMemberDetailsTest {
    private fun row(id: String, date: String, amount: Long, member: String = "妈妈") = LedgerEntry(
        id, EntryType.EXPENSE, date, amount, "生活", account = "现金", member = member, recordedBy = "爸爸")

    @Test fun memberDetailsUseOwnershipAndTheSamePeriodAndExclusionsAsTheReport() {
        val rows = listOf(row("early", "2026-09-03", 101), row("late", "2026-09-22", 202),
            row("other-owner", "2026-09-03", 999, "爸爸"), row("previous", "2026-08-31", 999),
            row("next", "2026-10-01", 999), row("deleted", "2026-09-03", 999).copy(deletedAt = 1),
            row("foreign", "2026-09-03", 999).copy(currency = "USD"),
            row("income", "2026-09-03", 999).copy(type = EntryType.INCOME),
            row("adjustment", "2026-09-03", 999).copy(type = EntryType.BALANCE_ADJUSTMENT))
        val report = buildReport(rows, LocalDate.parse("2026-09-15"), ReportPeriod.MONTH)
        val details = reportMemberEntries(rows, "妈妈", report.range)
        assertEquals(listOf("late", "early"), details.map { it.id })
        assertEquals(303L, details.sumOf { it.amountMinor })
        assertEquals(303L, report.members.single { it.name == "妈妈" }.amount)
        assertTrue(reportMemberEntries(rows, "不存在的成员", report.range).isEmpty())
    }

    @Test fun memberDetailsRespectAllFourSelectedPeriodsAndStableDateOrdering() {
        val rows = listOf(row("z", "2026-09-03", 2), row("a", "2026-09-03", 1),
            row("week", "2026-09-06", 4), row("month", "2026-09-30", 8), row("year", "2026-12-31", 16))
        val expected = mapOf(ReportPeriod.DAY to listOf("a", "z"), ReportPeriod.WEEK to listOf("week", "a", "z"),
            ReportPeriod.MONTH to listOf("month", "week", "a", "z"), ReportPeriod.YEAR to listOf("year", "month", "week", "a", "z"))
        for (period in ReportPeriod.entries) {
            val report = buildReport(rows, LocalDate.parse("2026-09-03"), period)
            assertEquals(expected[period], reportMemberEntries(rows, "妈妈", report.range).map { it.id })
        }
    }
}
