package com.familyledger.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.familyledger.app.MainActivity
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import org.junit.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import com.familyledger.app.LedgerApplication
import com.familyledger.app.domain.*
import kotlinx.coroutines.runBlocking
import java.time.YearMonth

class LedgerUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun singleTextEntryAndEditableLocalRole() {
        awaitText("账本助手")
        compose.onNodeWithContentDescription("发送").assertExists()
        compose.onNodeWithText("语音输入").assertDoesNotExist()
        compose.onNodeWithContentDescription("记一笔").assertDoesNotExist()
        compose.onNodeWithText("记收支，聊聊家庭财务…").performTextInput("午饭 36.80 元")
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("角色名称").performScrollTo().performTextReplacement("老公")
        compose.onNodeWithText("🐱 小猫").performScrollTo().performClick()
        compose.onNodeWithText("保存角色").performScrollTo().performClick()
        compose.onNodeWithText("记账").performClick()
        awaitText("老公 · 本机账本")
        compose.onNodeWithText("午饭 36.80 元").assertExists()
        screenshot("ledger-chat.png")
        compose.onNodeWithText("账本", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        screenshot("ledger-list.png")
        compose.onNodeWithText("报表", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        screenshot("ledger-report.png")
        compose.onNodeWithText("日报").performClick()
        compose.onNodeWithText("日报").assertIsSelected()
        screenshot("ledger-daily.png")
        compose.onNodeWithText("周报").performClick()
        compose.onNodeWithText("周报").assertIsSelected()
        screenshot("ledger-weekly.png")
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        screenshot("ledger-settings.png")
        compose.onNodeWithText("检查更新").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("管理家庭账号与同步").performScrollTo().performClick()
        compose.onNodeWithText("忘记密码？").performScrollTo().performClick()
        compose.onNodeWithText("找回密码").assertExists()
        screenshot("ledger-recovery.png")
    }

    @Test fun monthlyTrendCanSwitchZoomPanAndSelectExactAmounts() {
        val month = YearMonth.now()
        val repo = (compose.activity.application as LedgerApplication).repository
        val rows = (0 until 12).flatMap { offset ->
            val date = month.minusMonths(11L - offset).atDay(10).toString()
            listOf(
                LedgerEntry(java.util.UUID.randomUUID().toString(), EntryType.INCOME, date, 120000L + offset * 31000L,
                    "收入", account = "测试", member = "家人", recordedBy = "本人"),
                LedgerEntry(java.util.UUID.randomUUID().toString(), EntryType.EXPENSE, date, 45000L + (offset % 4) * 19000L,
                    "生活", account = "测试", member = "家人", recordedBy = "本人"))
        }
        runBlocking { repo.saveMany(rows) }
        try {
            awaitText("账本助手")
            compose.onNodeWithText("报表", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("report_list").performScrollToNode(hasTestTag("monthly_trend"))
            compose.onNodeWithTag("trend_range_12").performScrollTo().performClick().assertIsSelected()
            fun viewportText() = compose.onNodeWithTag("trend_viewport").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
            val full = viewportText()
            compose.onNodeWithTag("trend_zoom_in").performScrollTo().performClick()
            Assert.assertNotEquals(full, viewportText())
            val zoomed = viewportText()
            compose.onNodeWithTag("trend_chart").performScrollTo().performTouchInput { swipeLeft() }
            Assert.assertNotEquals(zoomed, viewportText())
            compose.onNodeWithTag("trend_reset").performScrollTo().performClick()
            Assert.assertEquals(full, viewportText())
            compose.onNodeWithTag("trend_chart").performScrollTo().performTouchInput {
                down(0, Offset(width * 0.4f, centerY))
                down(1, Offset(width * 0.6f, centerY))
                moveTo(0, Offset(width * 0.22f, centerY), delayMillis = 160)
                moveTo(1, Offset(width * 0.88f, centerY), delayMillis = 160)
                up(0); up(1)
            }
            Assert.assertNotEquals(full, viewportText())
            compose.onNodeWithTag("trend_reset").performScrollTo().performClick()
            compose.onNodeWithTag("trend_previous_month").performScrollTo().performClick()
            compose.onNodeWithTag("trend_selected_month").assertTextEquals(month.minusMonths(1).toString())
            compose.onNodeWithText("收入 ¥ 4300.00").assertExists()
            compose.onNodeWithText("支出 ¥ 830.00").assertExists()
            compose.onNodeWithTag("trend_chart").performScrollTo()
            screenshot("ledger-trend-year.png")
            compose.onNodeWithTag("trend_range_6").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("trend_chart").performScrollTo()
            screenshot("ledger-trend-six-months.png")
            compose.onNodeWithTag("trend_chart").performTouchInput { swipeUp() }
            compose.onNodeWithTag("report_list").performScrollToNode(hasText("AI 解读本期报告"))
            compose.onNodeWithText("AI 解读本期报告").assertIsDisplayed()
        } finally { runBlocking { rows.forEach { repo.delete(it.id) } } }
    }

    private fun awaitText(text: String) {
        awaitNode(hasText(text))
    }

    private fun awaitNode(matcher: SemanticsMatcher) {
        try {
            compose.waitUntil(15000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            screenshot("ui-failure.png")
            throw AssertionError("Missing UI node: $matcher; activity=${compose.activity.lifecycle.currentState}\n" +
                compose.onRoot(useUnmergedTree = true).printToString(), failure)
        }
    }

    private fun screenshot(name: String) {
        // UTP uninstalls the app after testing; shell-owned captures survive until CI pulls them.
        require(name.matches(Regex("[a-z-]+\\.png")))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("screencap -p /data/local/tmp/ledger-screens/$name").let { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }
}
