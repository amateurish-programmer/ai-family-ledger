package com.familyledger.app.domain

import java.time.YearMonth
import kotlin.math.roundToInt

/** UI-only monthly window. Does not change the report/AI/export period contract. */
fun buildMonthlyTrend(entries: List<LedgerEntry>, endMonth: YearMonth, months: Int): List<PeriodTotal> {
    require(months == 6 || months == 12)
    val first = endMonth.minusMonths(months - 1L)
    return (0 until months).map { offset ->
        val month = first.plusMonths(offset.toLong())
        val summary = summarize(entries, month.atDay(1), month.plusMonths(1).atDay(1))
        PeriodTotal(month.toString(), summary.income, summary.expense)
    }
}

/** Coordinates are month indices; floating point is used only for chart geometry. */
data class TrendViewport private constructor(val pointCount: Int, val start: Float, val span: Float) {
    fun pan(months: Float): TrendViewport = if (!months.isFinite()) this else
        copy(start = (start + months).coerceIn(0f, pointCount - 1f - span))

    fun zoom(factor: Float, focalFraction: Float = 0.5f): TrendViewport {
        if (!factor.isFinite() || factor <= 0f || !focalFraction.isFinite()) return this
        val focal = focalFraction.coerceIn(0f, 1f)
        val newSpan = (span / factor).coerceIn(2f, pointCount - 1f)
        return copy(start = (start + span * focal - newSpan * focal).coerceIn(0f, pointCount - 1f - newSpan), span = newSpan)
    }

    fun selectedIndex(fraction: Float): Int =
        (start + span * (if (fraction.isFinite()) fraction else 0f).coerceIn(0f, 1f)).roundToInt().coerceIn(0, pointCount - 1)

    companion object {
        fun full(pointCount: Int): TrendViewport {
            require(pointCount >= 3)
            return TrendViewport(pointCount, 0f, pointCount - 1f)
        }
    }
}
