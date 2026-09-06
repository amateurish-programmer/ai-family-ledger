package com.familyledger.app.domain

import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class MonthlyTrendTest {
    private fun row(date: String, cents: Long, type: EntryType = EntryType.EXPENSE) = LedgerEntry(
        id = "$date-$cents-$type", type = type, occurredOn = date, amountMinor = cents,
        categoryL1 = "日常", account = "银行卡", member = "家人", recordedBy = "操作者", updatedAt = 0)

    @Test fun sixMonthsCrossYearIncludeWholeAnchorMonthAndFillEmptyMonths() {
        val points = buildMonthlyTrend(listOf(row("2023-08-31", 999), row("2023-09-01", 1),
            row("2024-02-29", 2), row("2024-03-01", 999)), YearMonth.of(2024, 2), 6)
        assertEquals(listOf("2023-09", "2023-10", "2023-11", "2023-12", "2024-01", "2024-02"), points.map { it.period })
        assertEquals(listOf(1L, 0L, 0L, 0L, 0L, 2L), points.map { it.expense })
        assertTrue(points.all { it.income == 0L })
    }

    @Test fun yearWindowEndsAtSelectedMonthInsteadOfCalendarDecember() {
        val points = buildMonthlyTrend(listOf(row("2025-10-01", 3), row("2026-09-30", 5),
            row("2026-10-01", 999)), YearMonth.of(2026, 9), 12)
        assertEquals(12, points.size)
        assertEquals("2025-10", points.first().period)
        assertEquals("2026-09", points.last().period)
        assertEquals(3L, points.first().expense)
        assertEquals(5L, points.last().expense)
    }

    @Test fun sumsCentsExactlyIncludingLargeAmountsAndExcludesInvalidReportRows() {
        val rows = listOf(row("2026-09-01", Money.MAX_MINOR), row("2026-09-02", Money.MAX_MINOR),
            row("2026-09-03", 1), row("2026-09-04", 2), row("2026-09-05", 7, EntryType.INCOME),
            row("2026-09-06", 999, EntryType.BALANCE_ADJUSTMENT),
            row("2026-09-07", 998).copy(deletedAt = 1), row("2026-09-08", 997).copy(currency = "USD"))
        val point = buildMonthlyTrend(rows, YearMonth.of(2026, 9), 6).last()
        assertEquals(200_000_000_001L, point.expense)
        assertEquals(7L, point.income)
    }

    @Test(expected = ArithmeticException::class) fun overflowIsRejectedInsteadOfWrappingOrRounding() {
        buildMonthlyTrend(listOf(row("2026-09-01", Long.MAX_VALUE), row("2026-09-02", 1)), YearMonth.of(2026, 9), 6)
    }

    @Test fun viewportZoomKeepsFocalMonthAndPanClampsToBothEdges() {
        val full = TrendViewport.full(12)
        val zoomed = full.zoom(2f, 0.75f)
        assertEquals(5.5f, zoomed.span, 0.0001f)
        assertEquals(8.25f, zoomed.start + zoomed.span * 0.75f, 0.0001f)
        assertEquals(0f, zoomed.pan(-100f).start, 0f)
        assertEquals(5.5f, zoomed.pan(100f).start, 0f)
        assertEquals(full, zoomed.zoom(0.001f))
        assertEquals(2f, full.zoom(100f).span, 0f)
    }

    @Test fun invalidGestureNumbersCannotPoisonViewportAndSelectionStaysWithinData() {
        val view = TrendViewport.full(6).zoom(2f)
        assertEquals(view, view.zoom(Float.NaN))
        assertEquals(view, view.zoom(0f))
        assertEquals(view, view.pan(Float.POSITIVE_INFINITY))
        assertEquals(0, view.pan(-100f).selectedIndex(-1f))
        assertEquals(5, view.pan(100f).selectedIndex(2f))
        assertEquals(3, TrendViewport.full(6).selectedIndex(0.5f))
    }

    @Test fun repeatedZoomAndDragStayInBoundsAndResetRestoresAllMonths() {
        var view = TrendViewport.full(12)
        repeat(200) {
            view = view.zoom(if (it % 2 == 0) 1.8f else 0.8f, (it % 11) / 10f).pan(if (it % 3 == 0) -8f else 4f)
            assertTrue(view.start >= 0f)
            assertTrue(view.start + view.span <= 11.00001f)
            assertTrue(view.span in 2f..11f)
        }
        assertEquals(0f, TrendViewport.full(view.pointCount).start, 0f)
        assertEquals(11f, TrendViewport.full(view.pointCount).span, 0f)
    }
}
