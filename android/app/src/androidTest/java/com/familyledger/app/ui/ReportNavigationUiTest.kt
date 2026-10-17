package com.familyledger.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import com.familyledger.app.domain.*
import java.time.YearMonth
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

class ReportNavigationUiTest {
    @get:Rule val compose = createComposeRule()

    private fun entry(id: String, date: String, amount: Long) = LedgerEntry(
        id = id, type = EntryType.INCOME, occurredOn = date, amountMinor = amount,
        categoryL1 = "其他收入", account = "现金", member = "家人", recordedBy = "记录者",
        isGift = true, counterparty = "亲友"
    )

    private fun showReport(initialMonth: YearMonth = YearMonth.of(2026, 9)) {
        compose.setContent {
            var month by remember { mutableStateOf(initialMonth) }
            FamilyLedgerTheme {
                ReportContent(
                    entries = listOf(entry("current", "2026-09-07", 123), entry("old", "2025-01-01", 234)),
                    month = month, onMonth = { month = it }, syncing = false, busy = false,
                    aiReportKey = null, aiReportText = null, onAnalyze = { _, _ -> },
                    onExport = {}, onOrganizeGifts = {}
                )
            }
        }
    }

    @Test fun sectionsKeepSelectedPeriodAndAnchor() {
        showReport()
        compose.onNodeWithTag("report_period_previous").performClick()
        compose.onNodeWithText("年报").performClick()
        compose.onNodeWithTag("report_period_label").assertTextEquals("2026 年")
        listOf("categories", "members", "gifts", "overview").forEach { section ->
            compose.onNodeWithTag("report_section_$section").performClick().assertIsSelected()
            compose.onNodeWithText("年报").assertIsSelected()
            compose.onNodeWithTag("report_period_label").assertTextEquals("2026 年")
        }
        compose.onNodeWithText("月报").performClick()
        compose.onNodeWithTag("report_period_label").assertTextEquals("2026 年 8 月")
    }

    @Test fun giftHistorySurvivesSectionSwitchWithoutChangingReportPeriod() {
        showReport()
        compose.onNodeWithTag("report_section_gifts").performClick()
        compose.onNodeWithTag("gift_income").assertTextEquals("收到人情  ¥ 1.23")
        compose.onNodeWithText("全部历史").performClick()
        compose.onNodeWithTag("gift_income").assertTextEquals("收到人情  ¥ 3.57")
        compose.onNodeWithTag("report_section_members").performClick()
        compose.onNodeWithTag("report_section_gifts").performClick()
        compose.onNodeWithText("全部历史").assertIsSelected()
        compose.onNodeWithTag("gift_income").assertTextEquals("收到人情  ¥ 3.57")
        compose.onNodeWithTag("report_period_label").assertTextEquals("2026 年 9 月")
        compose.onNodeWithText("当前期间").performClick()
        compose.onNodeWithTag("gift_income").assertTextEquals("收到人情  ¥ 1.23")
    }

    @Test fun dailyAndWeeklyAnchorsAreSharedAcrossSections() {
        showReport()
        compose.onNodeWithText("日报").performClick()
        compose.onNodeWithTag("report_period_next").performClick()
        compose.onNodeWithTag("report_period_label").assertTextEquals("2026-09-02")
        compose.onNodeWithTag("report_section_categories").performClick()
        compose.onNodeWithTag("report_period_label").assertTextEquals("2026-09-02")
        compose.onNodeWithText("周报").performClick()
        compose.onNodeWithTag("report_period_next").performClick()
        compose.onNodeWithTag("report_section_gifts").performClick()
        compose.onNodeWithText("周报").assertIsSelected()
        compose.onNodeWithTag("report_period_label").assertTextEquals("2026-09-07 至 2026-09-13")
        compose.onNodeWithTag("gift_income").assertTextEquals("收到人情  ¥ 1.23")
        compose.onNodeWithText("日报").performClick()
        compose.onNodeWithTag("report_period_label").assertTextEquals("2026-09-09")
    }

    @Test fun returningToOverviewKeepsItsScrollPosition() {
        showReport()
        compose.onNodeWithTag("report_list").performScrollToNode(hasText("AI 解读本期报告"))
        compose.onNodeWithText("AI 解读本期报告").assertIsDisplayed()
        compose.onNodeWithTag("report_section_categories").performClick()
        compose.onNodeWithTag("report_section_overview").performClick()
        compose.onNodeWithText("AI 解读本期报告").assertIsDisplayed()
    }

    @Test fun overviewRoundTripPreservesChartViewport() {
        showReport()
        compose.onNodeWithTag("report_list").performScrollToNode(hasTestTag("monthly_trend"))
        compose.onNodeWithTag("trend_range_12").performScrollTo().performClick()
        compose.onNodeWithTag("trend_zoom_in").performScrollTo().performClick()
        fun viewport() = compose.onNodeWithTag("trend_viewport").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        val before = viewport()
        compose.onNodeWithTag("report_section_categories").performClick()
        compose.onNodeWithTag("report_section_overview").performClick()
        org.junit.Assert.assertEquals(before, viewport())
        compose.onNodeWithTag("trend_reset").performScrollTo().performClick()
        org.junit.Assert.assertNotEquals(before, viewport())
    }

    @Test fun currentMonthDailyReportStartsToday() {
        val today = LocalDate.now()
        showReport(YearMonth.from(today))
        compose.onNodeWithText("日报").performClick()
        compose.onNodeWithTag("report_period_label").assertTextEquals(today.toString())
    }

    @Test fun detailAnalysisRequiresDisclosureConfirmation() {
        var requests = 0
        compose.setContent {
            FamilyLedgerTheme {
                ReportContent(listOf(entry("current", "2026-09-07", 123)), YearMonth.of(2026, 9), {},
                    false, false, null, null, { report, _ ->
                        org.junit.Assert.assertEquals(LocalDate.parse("2026-09-01"), report.range.start)
                        requests++
                    }, {}, {})
            }
        }
        compose.onNodeWithTag("report_list").performScrollToNode(hasText("AI 解读本期报告"))
        compose.onNodeWithText("AI 解读本期报告").performClick()
        compose.onNodeWithText("解读本期消费明细？").assertIsDisplayed()
        compose.onNodeWithText("备注节选", substring = true).assertIsDisplayed()
        compose.onNodeWithText("本期及对照期间统计", substring = true).assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertEquals(0, requests) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(0, requests) }
        compose.onNodeWithText("AI 解读本期报告").performClick()
        compose.onNodeWithText("生成解读").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, requests) }
    }
}
