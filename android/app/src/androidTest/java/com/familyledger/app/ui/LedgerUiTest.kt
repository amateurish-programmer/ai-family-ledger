package com.familyledger.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.familyledger.app.MainActivity
import org.junit.*

class LedgerUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun recordExpenseAndSeeItInLedger() {
        awaitText("记一笔")
        compose.onNodeWithText("记一笔").performClick()
        compose.onNodeWithText("金额（元）").performTextInput("36.80")
        compose.onNodeWithText("保存记录").performClick()
        awaitText("−36.80")
        compose.onNodeWithText("−36.80").assertIsDisplayed()
        compose.onNodeWithText("报表").performClick()
        compose.onNodeWithText("支出去向").assertExists()
    }

    private fun awaitText(text: String) {
        try {
            compose.waitUntil(15000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Exception) {
            throw AssertionError("Missing UI text: $text; activity=${compose.activity.lifecycle.currentState}\n" +
                compose.onRoot(useUnmergedTree = true).printToString(), failure)
        }
    }
}
