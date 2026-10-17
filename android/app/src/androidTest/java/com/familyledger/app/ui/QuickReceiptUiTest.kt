package com.familyledger.app.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.familyledger.app.data.*
import com.familyledger.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class QuickReceiptUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: LedgerDatabase
    private lateinit var repository: LedgerRepository
    private lateinit var model: LedgerViewModel
    private val store = ViewModelStore()
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, LedgerDatabase::class.java).build()
        repository = LedgerRepository(database)
    }
    @After fun close() { compose.runOnIdle { store.clear() }; database.close() }

    private fun show(count: Int) {
        val drafts = (1..count).map { LedgerEntry("receipt-$it", EntryType.EXPENSE, "2026-10-17", 8600L,
            "食品酒水", account = "现金", member = "本人", recordedBy = "本人") }
        runBlocking { repository.saveChatDrafts("local", drafts) }
        compose.runOnIdle { model = LedgerViewModel(repository); store.put("receipt-test", model) }
        compose.setContent { FamilyLedgerTheme {
            val state by model.state.collectAsState()
            QuickEntryScreen(model, state, SnackbarHostState(), null, {}, state.conversationKey)
        } }
        compose.waitUntil(10000) { !model.state.value.loading && model.state.value.quickDrafts.size == count }
        assertTrue(runBlocking { repository.allEntries().isEmpty() })
    }
    @Test fun singleReceiptOnlySavesAfterItsConfirmation() {
        show(1)
        compose.onNodeWithText("确认入账").performScrollTo().performClick()
        assertSaved(1)
    }
    @Test fun multipleReceiptsKeepOneBatchConfirmation() {
        show(2)
        compose.onNodeWithText("确认入账").assertDoesNotExist()
        compose.onNodeWithText("确认保存 2 笔").performScrollTo().performClick()
        assertSaved(2)
    }
    private fun assertSaved(count: Int) {
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.quickDrafts.isEmpty() && model.state.value.entries.size == count }
        val saved = runBlocking { repository.allEntries() }
        assertEquals(count, saved.size)
        assertTrue(saved.all { it.amountMinor == 8600L && it.member == "本人" })
        assertTrue(runBlocking { repository.chatDrafts("local").isEmpty() })
    }
}
