package com.familyledger.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.familyledger.app.MainActivity
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import org.junit.*

class LedgerUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun singleTextEntryAndEditableLocalRole() {
        awaitText("账本助手")
        compose.onNodeWithText("发送").assertExists()
        compose.onNodeWithText("语音输入").assertDoesNotExist()
        compose.onNodeWithContentDescription("记一笔").assertDoesNotExist()
        compose.onNodeWithText("记收支，或问问账本…").performTextInput("午饭 36.80 元")
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("角色名称，例如老公、老婆").performTextReplacement("老公")
        compose.onNodeWithText("保存角色").performClick()
        compose.onNodeWithText("对话").performClick()
        awaitText("老公 · 本机账本")
        compose.onNodeWithText("午饭 36.80 元").assertExists()
        screenshot("ledger-chat.png")
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
