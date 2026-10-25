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

    @Test fun categoryFilterCombinesSubcategoriesButNeverOtherOwnersOrSimilarCategoryNames() {
        val food = row("food", "2026-10-01", 1, "老公").copy(categoryL1 = "食品酒水", categoryL2 = "买菜", recordedBy = "老婆")
        val rows = listOf(food, food.copy(id = "meal", occurredOn = "2026-10-25", amountMinor = 2, categoryL2 = "餐饮"),
            food.copy(id = "travel", categoryL1 = "交通", amountMinor = 400),
            food.copy(id = "similar", categoryL1 = "食品酒水其他", amountMinor = 500),
            food.copy(id = "wife", member = "老婆", recordedBy = "老公", amountMinor = 600),
            food.copy(id = "income", type = EntryType.INCOME), food.copy(id = "adjustment", type = EntryType.BALANCE_ADJUSTMENT),
            food.copy(id = "deleted", deletedAt = 1), food.copy(id = "foreign", currency = "USD"),
            food.copy(id = "previous", occurredOn = "2026-09-30"), food.copy(id = "next", occurredOn = "2026-11-01"))
        val range = ReportPeriod.MONTH.range(LocalDate.parse("2026-10-25"))
        val selected = reportMemberEntries(rows, "老公", range, "食品酒水")
        assertEquals(listOf("meal", "food"), selected.map { it.id })
        assertEquals(3L, selected.fold(0L) { total, entry -> Math.addExact(total, entry.amountMinor) })
        assertEquals(4, reportMemberEntries(rows, "老公", range).size)
        assertTrue(reportMemberEntries(rows, "老公", range, "不存在").isEmpty())
    }

    @Test fun selectedCategoryStillUsesDayWeekMonthAndYearBoundaries() {
        val rows = listOf(row("today", "2026-10-01", 1), row("week", "2026-10-04", 2),
            row("month", "2026-10-31", 4), row("year", "2026-12-31", 8),
            row("next-year", "2027-01-01", 16), row("other", "2026-10-01", 32).copy(categoryL1 = "交通"))
        val expected = mapOf(ReportPeriod.DAY to 1L, ReportPeriod.WEEK to 3L, ReportPeriod.MONTH to 7L, ReportPeriod.YEAR to 15L)
        expected.forEach { (period, amount) ->
            val selected = reportMemberEntries(rows, "妈妈", period.range(LocalDate.parse("2026-10-01")), "生活")
            assertEquals(amount, selected.fold(0L) { total, entry -> Math.addExact(total, entry.amountMinor) })
        }
    }
}
