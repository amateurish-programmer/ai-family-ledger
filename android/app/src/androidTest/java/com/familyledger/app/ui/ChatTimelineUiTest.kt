package com.familyledger.app.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.familyledger.app.data.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatTimelineUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var list: LazyListState
    private lateinit var scope: CoroutineScope
    private var messages by mutableStateOf(history("a", 80))
    private var owner by mutableStateOf("owner-a")
    private var visible by mutableStateOf(true)
    private var processing by mutableStateOf(false)

    private fun showTimeline() {
        compose.setContent {
            FamilyLedgerTheme {
                val holder = rememberSaveableStateHolder()
                if (visible) holder.SaveableStateProvider(owner) {
                    list = rememberSaveable(owner, saver = LazyListState.Saver) { LazyListState() }
                    scope = rememberCoroutineScope()
                    ChatTimeline(messages, owner, "本人", "person", Modifier.height(420.dp), list) {
                        if (processing) item(key = "test:processing") { Text("正在处理") }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun scrollTo(index: Int, offset: Int = 0) {
        compose.runOnIdle { scope.launch { list.scrollToItem(index, offset) } }
        compose.waitForIdle()
    }

    private fun anchor(): Pair<Any?, Int> = list.layoutInfo.visibleItemsInfo
        .firstOrNull { it.index == list.firstVisibleItemIndex }?.key to list.firstVisibleItemScrollOffset

    @Test fun opensAtLatestAndOnlyLoadsOlderMessagesAfterManualRequest() {
        showTimeline()
        compose.onNodeWithText("a message 80").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(31, list.layoutInfo.totalItemsCount)
            assertEquals(0, list.firstVisibleItemIndex)
        }
        scrollTo(30)
        compose.onNodeWithText("继续加载（还有 50 条）").assertIsDisplayed()
        val before = anchor()
        compose.onNodeWithText("继续加载（还有 50 条）").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(61, list.layoutInfo.totalItemsCount)
            assertEquals(before, anchor())
        }
        scrollTo(60)
        compose.onNodeWithText("继续加载（还有 20 条）").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(81, list.layoutInfo.totalItemsCount) }
        scrollTo(80)
        compose.onNodeWithText("已显示全部记录").assertIsDisplayed()
        compose.onNodeWithText("a message 1").assertIsDisplayed()
    }

    @Test fun processingChangesAndAssistantReplyKeepReadersMessageAndOffset() {
        showTimeline()
        scrollTo(12, 17)
        val before = anchor()
        compose.runOnIdle { processing = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(before, anchor()); processing = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(before, anchor())
            messages = messages + ChatMessage("a-81", false, "new assistant reply")
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(before, anchor()) }
        compose.onNodeWithText("new assistant reply").assertDoesNotExist()
    }

    @Test fun ownSendFollowsAndAssistantReplyFollowsWhenAlreadyNearLatest() {
        showTimeline()
        scrollTo(12, 17)
        compose.runOnIdle { messages = messages + ChatMessage("a-81", true, "my new question") }
        compose.waitForIdle()
        compose.onNodeWithText("my new question").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, list.firstVisibleItemIndex) }
        compose.runOnIdle { messages = messages + ChatMessage("a-82", false, "new assistant reply") }
        compose.waitForIdle()
        compose.onNodeWithText("new assistant reply").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, list.firstVisibleItemIndex) }
    }

    @Test fun returningToTabRestoresWindowAndOffsetButNewOwnerStartsWithRecentPage() {
        showTimeline()
        scrollTo(30)
        compose.onNodeWithText("继续加载（还有 50 条）").performClick()
        compose.waitForIdle()
        scrollTo(45, 17)
        val before = anchor()
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle {
            messages = messages + ChatMessage("a-81", false, "reply while another tab is open")
            visible = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(62, list.layoutInfo.totalItemsCount)
            assertEquals(before, anchor())
            owner = "owner-b"
            messages = history("b", 90)
        }
        compose.waitForIdle()
        compose.onNodeWithText("b message 90").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(31, list.layoutInfo.totalItemsCount)
            assertEquals(0, list.firstVisibleItemIndex)
        }
        scrollTo(30)
        compose.onNodeWithText("继续加载（还有 60 条）").assertIsDisplayed()
        compose.onNodeWithText("a message 80").assertDoesNotExist()
    }

    private companion object {
        fun history(prefix: String, count: Int) = (1..count).map {
            ChatMessage("$prefix-$it", it % 2 == 1, "$prefix message $it")
        }
    }
}
