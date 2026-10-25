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

    @Test fun categoryRowsOpenExpenseAndIncomeDetails() {
        compose.setContent {
            FamilyLedgerTheme {
                ReportContent(listOf(
                    entry("e1", "2026-09-03", 12000).copy(type = EntryType.EXPENSE, isGift = false,
                        categoryL1 = "养娃", categoryL2 = "奶粉"),
                    entry("e2", "2026-09-08", 5600).copy(type = EntryType.EXPENSE, isGift = false,
                        categoryL1 = "养娃", categoryL2 = "玩具"),
                    entry("i1", "2026-09-05", 800000).copy(isGift = false, categoryL1 = "工资"),
                    entry("i2", "2025-01-01", 999999).copy(isGift = false, categoryL1 = "工资")),
                    YearMonth.of(2026, 9), {}, false, false, null, null, { _, _ -> }, {}, {})
            }
        }
        compose.onNodeWithTag("report_section_categories").performClick()
        compose.onNodeWithText("支出去向").assertIsDisplayed()
        compose.onNodeWithTag("report_expense_category_0").performClick()
        compose.onNodeWithText("合计 ¥ 176.00 · 2 笔").assertIsDisplayed()
        compose.onNodeWithText("2026-09-08 · 支出 ¥ 56.00").assertIsDisplayed()
        compose.onNodeWithText("养娃 · 玩具 · 家人 · 现金").assertIsDisplayed()
        compose.onNodeWithText("2026-09-03 · 支出 ¥ 120.00").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("category_detail").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("report_income_source_0").performScrollTo().performClick()
        compose.onNodeWithText("合计 ¥ 8000.00 · 1 笔").assertIsDisplayed()
        compose.onNodeWithText("2026-09-05 · 收入 ¥ 8000.00").assertIsDisplayed()
        compose.onNodeWithText("工资 · 家人 · 现金").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("category_detail").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun currentMonthDailyReportStartsToday() {
        val today = LocalDate.now()
        showReport(YearMonth.from(today))
        compose.onNodeWithText("日报").performClick()
        compose.onNodeWithTag("report_period_label").assertTextEquals(today.toString())
    }

    @Test fun memberRowOpensOnlyThatMembersCurrentPeriodExpenseDetails() {
        compose.setContent {
            FamilyLedgerTheme {
                ReportContent(listOf(
                    entry("m1", "2026-09-03", 12000).copy(type = EntryType.EXPENSE, isGift = false,
                        member = "妈妈", recordedBy = "爸爸", categoryL1 = "养娃", categoryL2 = "奶粉"),
                    entry("m2", "2026-09-08", 5600).copy(type = EntryType.EXPENSE, isGift = false,
                        member = "妈妈", recordedBy = "爸爸", categoryL1 = "生活", categoryL2 = "买菜"),
                    entry("other", "2026-09-04", 100).copy(type = EntryType.EXPENSE, member = "爸爸"),
                    entry("income", "2026-09-05", 800000).copy(member = "妈妈"),
                    entry("old", "2025-01-01", 999999).copy(type = EntryType.EXPENSE, member = "妈妈")),
                    YearMonth.of(2026, 9), {}, false, false, null, null, { _, _ -> }, {}, {})
            }
        }
        compose.onNodeWithTag("report_section_members").performClick()
        compose.onNodeWithTag("report_member_0").assertHasClickAction().performClick()
        compose.onNodeWithText("成员支出明细 · 2026 年 9 月").assertIsDisplayed()
        compose.onNodeWithText("合计 ¥ 176.00 · 2 笔").assertIsDisplayed()
        compose.onNodeWithText("2026-09-08 · 支出 ¥ 56.00").assertIsDisplayed()
        compose.onNodeWithText("生活 · 买菜 · 妈妈 · 现金").assertIsDisplayed()
        compose.onNodeWithText("养娃 · 奶粉 · 妈妈 · 现金").assertIsDisplayed()
        compose.onNodeWithText("2026-09-04 · 支出 ¥ 1.00").assertDoesNotExist()
        compose.onNodeWithText("关闭").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("member_detail").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("report_period_label").assertTextEquals("2026 年 9 月")
        compose.onNodeWithTag("report_member_0").assertIsDisplayed()
    }

    @Test fun memberDetailsExcludeInvalidRowsAndUseOwnerRatherThanRecorder() {
        compose.setContent {
            FamilyLedgerTheme {
                val owned = entry("owned", "2026-09-01", 123).copy(type = EntryType.EXPENSE,
                    member = "妈妈", recordedBy = "爸爸", categoryL1 = "生活", note = "本期归属妈妈")
                ReportContent(listOf(owned,
                    owned.copy(id = "recorded", member = "爸爸", recordedBy = "妈妈", amountMinor = 200),
                    owned.copy(id = "similar", member = "妈妈的账户", amountMinor = 300),
                    owned.copy(id = "income", type = EntryType.INCOME, amountMinor = 400),
                    owned.copy(id = "adjustment", type = EntryType.BALANCE_ADJUSTMENT, amountMinor = 500),
                    owned.copy(id = "deleted", deletedAt = 1, amountMinor = 600),
                    owned.copy(id = "foreign", currency = "USD", amountMinor = 700),
                    owned.copy(id = "before", occurredOn = "2026-08-31", amountMinor = 800),
                    owned.copy(id = "after", occurredOn = "2026-10-01", amountMinor = 900)),
                    YearMonth.of(2026, 9), {}, false, false, null, null, { _, _ -> }, {}, {})
            }
        }
        compose.onNodeWithTag("report_section_members").performClick()
        compose.onNodeWithText("妈妈").performClick()
        compose.onNodeWithText("合计 ¥ 1.23 · 1 笔").assertIsDisplayed()
        compose.onNodeWithText("2026-09-01 · 支出 ¥ 1.23").assertIsDisplayed()
        compose.onNodeWithText("生活 · 妈妈 · 现金").assertIsDisplayed()
        compose.onNodeWithText("本期归属妈妈").assertIsDisplayed()
    }

    @Test fun memberCategorySelectionUpdatesExactTotalDetailsAndResetsOnReopen() {
        val food = entry("food", "2026-09-03", 12001).copy(type = EntryType.EXPENSE, isGift = false,
            member = "老公", recordedBy = "老婆", categoryL1 = "食品酒水", categoryL2 = "买菜")
        var rows by mutableStateOf(listOf(food,
            food.copy(id = "meal", occurredOn = "2026-09-08", amountMinor = 5602, categoryL2 = "餐饮"),
            food.copy(id = "travel", amountMinor = 3000, categoryL1 = "交通", categoryL2 = "地铁"),
            food.copy(id = "other-owner", member = "老婆", recordedBy = "老公", amountMinor = 999),
            food.copy(id = "old", occurredOn = "2026-08-31", amountMinor = 999)))
        compose.setContent {
            FamilyLedgerTheme {
                ReportContent(rows, YearMonth.of(2026, 9), {}, false, false, null, null, { _, _ -> }, {}, {})
            }
        }
        compose.onNodeWithTag("report_section_members").performClick()
        compose.onNodeWithTag("report_member_0").performClick()
        compose.onNodeWithText("合计 ¥ 206.03 · 3 笔").assertIsDisplayed()
        compose.onNodeWithTag("member_category_filter").performClick()
        compose.onNodeWithText("食品酒水").performClick()
        compose.onNodeWithText("支出分类：食品酒水").assertIsDisplayed()
        compose.onNodeWithText("合计 ¥ 176.03 · 2 笔").assertIsDisplayed()
        compose.onNodeWithText("2026-09-08 · 支出 ¥ 56.02").assertIsDisplayed()
        compose.onNodeWithText("交通 · 地铁 · 老公 · 现金").assertDoesNotExist()
        compose.onNodeWithTag("member_category_filter").performClick()
        compose.onNodeWithText("交通").performClick()
        compose.onNodeWithText("合计 ¥ 30.00 · 1 笔").assertIsDisplayed()
        compose.onNodeWithText("交通 · 地铁 · 老公 · 现金").assertIsDisplayed()
        // A sync or edit can remove the selected category while this sheet is open.
        compose.runOnIdle { rows = rows.filterNot { it.categoryL1 == "交通" } }
        compose.onNodeWithText("合计 ¥ 0.00 · 0 笔").assertIsDisplayed()
        compose.onNodeWithText("本期该分类暂无支出。").assertIsDisplayed()
        compose.onNodeWithTag("member_category_filter").performClick()
        compose.onNodeWithText("全部分类").performClick()
        compose.onNodeWithText("合计 ¥ 176.03 · 2 笔").assertIsDisplayed()
        compose.onNodeWithTag("member_category_filter").performClick()
        compose.onNodeWithText("食品酒水").performClick()
        compose.onNodeWithText("关闭").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("member_detail").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("report_member_0").performClick()
        compose.onNodeWithText("支出分类：全部分类").assertIsDisplayed()
    }

    @Test fun sameNamedMemberAndCategoriesKeepIndependentDetailFilters() {
        compose.setContent {
            FamilyLedgerTheme {
                ReportContent(listOf(
                    entry("category", "2026-09-03", 12000).copy(type = EntryType.EXPENSE,
                        member = "妈妈", categoryL1 = "养娃"),
                    entry("member", "2026-09-08", 5600).copy(type = EntryType.EXPENSE,
                        member = "养娃", categoryL1 = "生活"),
                    entry("income", "2026-09-05", 90000).copy(member = "妈妈", categoryL1 = "养娃")),
                    YearMonth.of(2026, 9), {}, false, false, null, null, { _, _ -> }, {}, {})
            }
        }
        compose.onNodeWithTag("report_section_members").performClick()
        compose.onNodeWithTag("report_member_1").performClick()
        compose.onNodeWithText("成员支出明细 · 2026 年 9 月").assertIsDisplayed()
        compose.onNodeWithText("合计 ¥ 56.00 · 1 笔").assertIsDisplayed()
        compose.onNodeWithText("生活 · 养娃 · 现金").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("member_detail").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("report_section_categories").performClick()
        compose.onNodeWithTag("report_expense_category_0").performClick()
        compose.onNodeWithText("支出去向明细 · 2026 年 9 月").assertIsDisplayed()
        compose.onNodeWithText("合计 ¥ 120.00 · 1 笔").assertIsDisplayed()
        compose.onNodeWithText("养娃 · 妈妈 · 现金").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("category_detail").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("report_income_source_0").performScrollTo().performClick()
        compose.onNodeWithText("收入来源明细 · 2026 年 9 月").assertIsDisplayed()
        compose.onNodeWithText("合计 ¥ 900.00 · 1 笔").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("category_detail").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("report_section_members").performClick()
        compose.onNodeWithTag("report_member_0").performClick()
        compose.onNodeWithText("成员支出明细 · 2026 年 9 月").assertIsDisplayed()
        compose.onNodeWithText("合计 ¥ 120.00 · 1 笔").assertIsDisplayed()
    }

    @Test fun memberDetailsFollowAllReportRangesAndCloseWhenMonthChanges() {
        var month by mutableStateOf(YearMonth.of(2026, 9))
        compose.setContent {
            FamilyLedgerTheme {
                val expense = entry("start", "2026-09-01", 123).copy(type = EntryType.EXPENSE)
                ReportContent(listOf(expense,
                    expense.copy(id = "weekStart", occurredOn = "2026-08-31", amountMinor = 4),
                    expense.copy(id = "weekEnd", occurredOn = "2026-09-06", amountMinor = 5),
                    expense.copy(id = "nextWeek", occurredOn = "2026-09-07", amountMinor = 6),
                    expense.copy(id = "monthEnd", occurredOn = "2026-09-30", amountMinor = 7),
                    expense.copy(id = "nextMonth", occurredOn = "2026-10-01", amountMinor = 8),
                    expense.copy(id = "yearStart", occurredOn = "2026-01-01", amountMinor = 9),
                    expense.copy(id = "yearEnd", occurredOn = "2026-12-31", amountMinor = 10),
                    expense.copy(id = "previousYear", occurredOn = "2025-12-31", amountMinor = 1000),
                    expense.copy(id = "nextYear", occurredOn = "2027-01-01", amountMinor = 2000)),
                    month, { month = it }, false, false, null, null, { _, _ -> }, {}, {})
            }
        }
        compose.onNodeWithTag("report_section_members").performClick()
        listOf(
            Triple("日报", "2026-09-01", "合计 ¥ 1.23 · 1 笔"),
            Triple("周报", "2026-08-31 至 2026-09-06", "合计 ¥ 1.32 · 3 笔"),
            Triple("月报", "2026 年 9 月", "合计 ¥ 1.41 · 4 笔"),
            Triple("年报", "2026 年", "合计 ¥ 1.72 · 8 笔")
        ).forEach { (period, label, total) ->
            compose.onNodeWithText(period).performClick()
            compose.onNodeWithTag("report_member_0").performClick()
            compose.onNodeWithText("成员支出明细 · $label").assertIsDisplayed()
            compose.onNodeWithText(total).assertIsDisplayed()
            compose.onNodeWithText("关闭").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("member_detail").fetchSemanticsNodes().isEmpty() }
        }
        compose.onNodeWithText("月报").performClick()
        compose.onNodeWithTag("report_member_0").performClick()
        compose.runOnIdle { month = YearMonth.of(2026, 10) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("member_detail").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("report_period_label").assertTextEquals("2026 年 10 月")
        compose.onNodeWithTag("report_member_0").performClick()
        compose.onNodeWithText("成员支出明细 · 2026 年 10 月").assertIsDisplayed()
        compose.onNodeWithText("合计 ¥ 0.08 · 1 笔").assertIsDisplayed()
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
