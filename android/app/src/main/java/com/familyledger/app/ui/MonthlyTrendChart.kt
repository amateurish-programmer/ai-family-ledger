package com.familyledger.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familyledger.app.domain.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

@Composable
fun MonthlyTrendChart(entries: List<LedgerEntry>, endMonth: YearMonth) {
    var months by rememberSaveable { mutableIntStateOf(6) }
    val points = remember(entries, endMonth, months) { buildMonthlyTrend(entries, endMonth, months) }
    var viewport by remember(endMonth, months) { mutableStateOf(TrendViewport.full(months)) }
    var selected by rememberSaveable(endMonth.toString(), months) { mutableIntStateOf(months - 1) }
    val incomeColor = LedgerSage
    val expenseColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    val textMeasurer = rememberTextMeasurer()
    val maximum = (points.maxOfOrNull { maxOf(it.income, it.expense) } ?: 0L).coerceAtLeast(1L)
    val selectedPoint = points[selected]
    fun selectMonth(index: Int) {
        selected = index.coerceIn(0, points.lastIndex)
        if (selected < viewport.start) viewport = viewport.pan(selected - viewport.start)
        if (selected > viewport.start + viewport.span) viewport = viewport.pan(selected - viewport.start - viewport.span)
    }

    Column(Modifier.fillMaxWidth().testTag("monthly_trend"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeading("收支趋势", "截至 ${endMonth.year} 年 ${endMonth.monthValue} 月 · 按月汇总")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(6 to "近六个月", 12 to "近一年").forEach { (count, label) ->
                FilterChip(selected = months == count, onClick = { months = count }, label = { Text(label) },
                    modifier = Modifier.testTag("trend_range_$count").heightIn(min = 48.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("━ 收入", color = incomeColor, style = MaterialTheme.typography.labelMedium)
                Text("┄ 支出", color = expenseColor, style = MaterialTheme.typography.labelMedium)
            }
            Text("金额（元）", style = MaterialTheme.typography.labelSmall)
        }
        Canvas(Modifier.fillMaxWidth().height(238.dp).testTag("trend_chart")
            .semantics { contentDescription = "月度收支曲线，收入实线，支出虚线。左右拖动平移，双指缩放，点击选择月份。" }
            .pointerInput(months, endMonth) {
                val left = 60.dp.toPx()
                val right = 14.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var travel = Offset.Zero
                    var transforming = false
                    var tap = true
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.isConsumed }) return@awaitEachGesture
                        val pan = event.calculatePan()
                        travel += pan
                        val twoFingers = event.changes.count { it.pressed } >= 2
                        if (twoFingers) { transforming = true; tap = false }
                        if (!transforming && travel.getDistance() > viewConfiguration.touchSlop) {
                            tap = false
                            // Let the report LazyColumn own vertical one-finger movement.
                            if (abs(travel.y) >= abs(travel.x)) return@awaitEachGesture
                            transforming = true
                        }
                        if (transforming) {
                            val width = (size.width - left - right).coerceAtLeast(1f)
                            val focal = ((event.calculateCentroid(useCurrent = false).x - left) / width).coerceIn(0f, 1f)
                            viewport = viewport.zoom(event.calculateZoom(), focal).pan(-pan.x / width * viewport.span)
                            event.changes.forEach { it.consume() }
                        }
                        if (event.changes.none { it.pressed } && tap && down.position.x in left..(size.width - right)) {
                            selectMonth(viewport.selectedIndex((down.position.x - left) / (size.width - left - right).coerceAtLeast(1f)))
                        }
                    } while (event.changes.any { it.pressed })
                }
            }) {
            val left = 60.dp.toPx()
            val right = size.width - 14.dp.toPx()
            val top = 12.dp.toPx()
            val bottom = size.height - 30.dp.toPx()
            val plotWidth = (right - left).coerceAtLeast(1f)
            fun x(index: Int) = left + (index - viewport.start) / viewport.span * plotWidth
            fun y(cents: Long) = bottom - (cents.toDouble() / maximum.toDouble()).toFloat() * (bottom - top)
            listOf(0L, maximum / 2, maximum).distinct().forEach { cents ->
                val ordinate = y(cents)
                drawLine(gridColor, Offset(left, ordinate), Offset(right, ordinate), strokeWidth = 1.dp.toPx())
                val label = textMeasurer.measure(trendAxisLabel(cents), labelStyle)
                drawText(label, topLeft = Offset((left - label.size.width - 7.dp.toPx()).coerceAtLeast(0f), ordinate - label.size.height / 2f))
            }
            val firstVisible = ceil(viewport.start).toInt().coerceIn(0, points.lastIndex)
            val lastVisible = floor(viewport.start + viewport.span).toInt().coerceIn(firstVisible, points.lastIndex)
            var lastLabelRight = -1f
            for (index in firstVisible..lastVisible) {
                val label = textMeasurer.measure(points[index].period, labelStyle)
                val labelLeft = (x(index) - label.size.width / 2f).coerceIn(0f, (size.width - label.size.width).coerceAtLeast(0f))
                if (labelLeft >= lastLabelRight + 6.dp.toPx()) {
                    drawText(label, topLeft = Offset(labelLeft, bottom + 8.dp.toPx()))
                    lastLabelRight = labelLeft + label.size.width
                }
            }
            clipRect(left, top - 5.dp.toPx(), right, bottom + 5.dp.toPx()) {
                if (selected.toFloat() in viewport.start..(viewport.start + viewport.span)) {
                    drawLine(gridColor, Offset(x(selected), top), Offset(x(selected), bottom), strokeWidth = 2.dp.toPx())
                }
                listOf(true, false).forEach { income ->
                    val color = if (income) incomeColor else expenseColor
                    val path = Path()
                    points.forEachIndexed { index, point ->
                        val ordinate = y(if (income) point.income else point.expense)
                        if (index == 0) path.moveTo(x(index), ordinate) else path.lineTo(x(index), ordinate)
                    }
                    drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(),
                        pathEffect = if (income) null else PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 4.dp.toPx()))))
                    points.forEachIndexed { index, point ->
                        drawCircle(color, radius = if (index == selected) 4.dp.toPx() else 2.5.dp.toPx(),
                            center = Offset(x(index), y(if (income) point.income else point.expense)))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { viewport = viewport.zoom(1.5f) }, enabled = viewport.span > 2f,
                modifier = Modifier.weight(1f).testTag("trend_zoom_in"), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("放大 ＋") }
            OutlinedButton(onClick = { viewport = viewport.zoom(1f / 1.5f) }, enabled = viewport.span < months - 1f,
                modifier = Modifier.weight(1f).testTag("trend_zoom_out"), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("缩小 −") }
            TextButton(onClick = { viewport = TrendViewport.full(months); selected = months - 1 },
                modifier = Modifier.testTag("trend_reset")) { Text("复位") }
        }
        Text("视窗 ${points[viewport.selectedIndex(0f)].period} 至 ${points[viewport.selectedIndex(1f)].period}",
            Modifier.testTag("trend_viewport"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { selectMonth(selected - 1) }, enabled = selected > 0,
                        modifier = Modifier.testTag("trend_previous_month"), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("上个月") }
                    Text(selectedPoint.period, Modifier.testTag("trend_selected_month"), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { selectMonth(selected + 1) }, enabled = selected < points.lastIndex,
                        modifier = Modifier.testTag("trend_next_month"), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("下个月") }
                }
                Text("收入 ¥ ${Money.format(selectedPoint.income)}", color = incomeColor, style = MaterialTheme.typography.bodyMedium)
                Text("支出 ¥ ${Money.format(selectedPoint.expense)}", color = expenseColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text("左右拖动平移 · 双指缩放 · 点击月份查看金额\n上下滑动继续浏览；零值表示本机暂无对应收支。图表视窗不改变本期统计、导出或 AI 解读范围。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Axis abbreviation is display-only; selected values always use exact Money.format. */
private fun trendAxisLabel(cents: Long): String {
    val yuan = BigDecimal.valueOf(cents, 2)
    val (divisor, suffix) = when {
        cents >= 100_000_000_000_000L -> BigDecimal("1000000000000") to "万亿"
        cents >= 10_000_000_000L -> BigDecimal("100000000") to "亿"
        cents >= 1_000_000L -> BigDecimal("10000") to "万"
        else -> BigDecimal.ONE to ""
    }
    return yuan.divide(divisor, if (divisor == BigDecimal.ONE) 2 else 1, RoundingMode.DOWN).stripTrailingZeros().toPlainString() + suffix
}
