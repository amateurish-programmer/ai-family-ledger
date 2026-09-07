package com.familyledger.app.ui

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.familyledger.app.domain.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GiftReportUiTest {
    @get:Rule val compose = createComposeRule()
    private fun row(id: String, date: String, name: String = "对象甲") = LedgerEntry(id = id,
        type = EntryType.INCOME, occurredOn = date, amountMinor = 123, categoryL1 = "其他收入",
        account = "现金", member = "成员", recordedBy = "操作者", isGift = true, counterparty = name, note = "合成备注$id")

    @Test fun rangeToggleAndCounterpartDetailsAndOrganizer() {
        var organized = false
        compose.setContent { FamilyLedgerTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                GiftReportSection(listOf(row("current", "2026-09-07"), row("old", "2025-01-01")),
                    ReportPeriod.MONTH.range(LocalDate.parse("2026-09-07"))) { organized = true }
            }
        } }
        compose.onNodeWithTag("gift_income").assertTextEquals("收到人情  ¥ 1.23")
        compose.onNodeWithText("全部历史").performClick()
        compose.onNodeWithTag("gift_income").assertTextEquals("收到人情  ¥ 2.46")
        compose.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /data/local/tmp/ledger-screens/gift-report.png")
        ParcelFileDescriptor.AutoCloseInputStream(screenshot).use { it.readBytes() }
        compose.onNodeWithTag("gift_income_0").performScrollTo().performClick()
        compose.onNodeWithTag("gift_detail").assertExists()
        compose.onNodeWithText("合成备注old").assertExists()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("gift_organize").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(organized) }
    }

    @Test fun pendingHasDetailsButNoRank() {
        compose.setContent { FamilyLedgerTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                GiftReportSection(listOf(row("pending", "2026-09-07", "  ")),
                    ReportPeriod.DAY.range(LocalDate.parse("2026-09-07"))) {}
            }
        } }
        compose.onNodeWithTag("gift_income_0").assertDoesNotExist()
        compose.onNodeWithTag("gift_pending").performScrollTo().performClick()
        compose.onNodeWithText("合成备注pending").assertExists()
    }

    @Test fun editorPreservesAndSavesOptionalGiftMetadata() {
        var saved: LedgerEntry? = null
        compose.setContent { FamilyLedgerTheme {
            EntryEditor(row("edit", "2026-09-07"), false, SnackbarHostState(), {}, { saved = it })
        } }
        compose.onNodeWithContentDescription("人情往来标记").performScrollTo().assertIsOn()
        compose.onNodeWithText("往来对象（可选）").performScrollTo().performTextReplacement("  对象乙  ")
        compose.onNodeWithText("保存记录").performClick()
        compose.runOnIdle {
            assertEquals("对象乙", saved?.counterparty)
            assertEquals(true, saved?.isGift)
            assertEquals(123L, saved?.amountMinor)
        }
    }
}
