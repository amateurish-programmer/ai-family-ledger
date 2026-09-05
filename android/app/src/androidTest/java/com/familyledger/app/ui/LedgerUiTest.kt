package com.familyledger.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.familyledger.app.MainActivity
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import org.junit.*

class LedgerUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun recordExpenseAndSeeItInLedger() {
        awaitNode(hasContentDescription("记一笔"))
        compose.onNodeWithContentDescription("记一笔").assertHasClickAction().performClick()
        compose.onNodeWithText("金额（元）").performTextInput("36.80")
        compose.onNodeWithText("保存记录").performClick()
        awaitText("−36.80")
        compose.onNodeWithText("−36.80").assertIsDisplayed()
        screenshot("ledger-recorded.png")
        compose.onNodeWithText("报表").performClick()
        compose.onNodeWithText("支出去向").assertExists()
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
