package com.familyledger.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.familyledger.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class ChatStartupUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val executor = Executors.newSingleThreadExecutor()
    private val gate = CountDownLatch(1)
    private val store = ViewModelStore()
    private lateinit var db: LedgerDatabase
    private lateinit var repository: LedgerRepository
    private lateinit var model: LedgerViewModel

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).setQueryExecutor(executor).build()
        repository = LedgerRepository(db)
    }

    @After fun cleanup() {
        gate.countDown()
        compose.runOnIdle { store.clear() }
        db.close()
        executor.shutdownNow()
    }

    private fun openWhileHistoryIsLoading() {
        executor.execute { gate.await() }
        compose.runOnIdle { model = LedgerViewModel(repository); store.put("startup", model) }
        compose.setContent { FamilyLedgerTheme { LedgerApp(model) } }
        try {
            compose.onNodeWithText("正在读取记账记录…").assertIsDisplayed()
        } catch (failure: AssertionError) {
            throw AssertionError("Startup history=${model.state.value.conversationLoad}\n" +
                compose.onRoot(useUnmergedTree = true).printToString(), failure)
        }
        compose.onNodeWithTag("chat_welcome").assertDoesNotExist()
        compose.onNodeWithText("登录并加入家庭，开始记账").assertDoesNotExist()
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
    }

    @Test fun existingHistoryAppearsOnFirstTabWithoutSwitching() {
        runBlocking {
            (1..45).forEach { repository.appendChat("local", ChatMessage("history-$it", false, "已有记账记录 $it"), emptyList()) }
            repository.appendChat("other-account", ChatMessage("private", false, "其他账号的记录"), emptyList())
        }
        openWhileHistoryIsLoading()
        gate.countDown()
        compose.waitUntil(10_000) { !model.state.value.loading }
        compose.onNodeWithText("已有记账记录 45").assertIsDisplayed()
        compose.onNodeWithTag("chat_welcome").assertDoesNotExist()
        compose.onNodeWithText("其他账号的记录").assertDoesNotExist()
        assertEquals(ConversationLoad.READY, model.state.value.conversationLoad)
        compose.onNodeWithText("账本", useUnmergedTree = true).performClick()
        compose.onNodeWithText("记账", useUnmergedTree = true).performClick()
        compose.onNodeWithText("已有记账记录 45").assertIsDisplayed()
    }

    @Test fun firstUseShowsWelcomeOnlyAfterAnEmptyHistoryHasLoaded() {
        openWhileHistoryIsLoading()
        gate.countDown()
        compose.waitUntil(10_000) { !model.state.value.loading }
        compose.onNodeWithTag("chat_welcome").assertIsDisplayed()
        compose.onNodeWithTag("chat_history_status").assertDoesNotExist()
    }

    @Test fun failedHistoryReadIsNotWelcomeAndCanBeRetriedWithoutSwitchingTabs() {
        runBlocking { db.conversationDao().saveDrafts(ChatDraftEntity("local", "invalid synthetic JSON")) }
        openWhileHistoryIsLoading()
        gate.countDown()
        compose.waitUntil(10_000) { !model.state.value.loading }
        compose.onNodeWithText("记账记录暂时无法读取，请重试。").assertIsDisplayed()
        compose.onNodeWithTag("chat_welcome").assertDoesNotExist()
        runBlocking {
            repository.appendChat("local", ChatMessage("recovered", false, "恢复的记账记录"), emptyList())
        }
        compose.onNodeWithText("重新读取").performClick()
        compose.waitUntil(10_000) { model.state.value.conversationLoad == ConversationLoad.READY }
        compose.onNodeWithText("恢复的记账记录").assertIsDisplayed()
        compose.onNodeWithTag("chat_welcome").assertDoesNotExist()
    }
}
