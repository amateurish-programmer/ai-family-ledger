package com.familyledger.app.ui

import android.content.Context
import androidx.room.Room
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.familyledger.app.data.*
import com.familyledger.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class LedgerRefreshTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    @Test fun startupReminderDefersForVisibleDraftAndLedgerEditors() {
        val draft = LedgerEntry("a909b8fe-6638-4b9e-bb6e-b04643b27022", EntryType.EXPENSE, "2026-09-07", 100,
            "食品", account = "测试账户", member = "家人", recordedBy = "测试")
        val state = LedgerState(loading = false, quickDrafts = listOf(draft))
        assertTrue(startupUpdateBlocked(state, 0, null, draft.id))
        assertFalse(startupUpdateBlocked(state, 0, null, null))
        assertFalse(startupUpdateBlocked(state, 1, null, draft.id))
        assertFalse(startupUpdateBlocked(state.copy(quickDrafts = emptyList()), 0, null, draft.id))
        assertTrue(startupUpdateBlocked(state, 1, draft.id, null))
        assertTrue(startupUpdateBlocked(state.copy(documentPickerOpen = true), 1, null, null))
    }

    @Test fun manualRefreshAppliesRemoteRowsAndRejectsDuplicateWhileRunning() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val repo = LedgerRepository(db)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val row = LedgerEntry("3af5a1da-b435-48f5-a5aa-b321667b74bd", EntryType.EXPENSE, "2026-09-07", 1290,
            "食品", account = "测试账户", member = "家人", recordedBy = "测试")
        val model = LedgerViewModel(repo, syncAction = { _, applyRemote, progress ->
            calls++; progress("下载家庭账本"); entered.complete(Unit); release.await()
            applyRemote(listOf(row)); SyncResult(0, 1, emptyList())
        })
        try {
            withTimeout(5000) { model.state.first { !it.loading } }
            main { model.syncCloud() }; withTimeout(5000) { entered.await() }
            assertTrue(model.state.value.syncing); assertTrue(model.state.value.busy)
            main { model.syncCloud() }; assertEquals(1, calls)
            release.complete(Unit)
            withTimeout(5000) { model.state.first { !it.busy && it.entries.any { entry -> entry.id == row.id } } }
            assertFalse(model.state.value.syncing)
            // A second manual request is allowed immediately after success.
            main { model.syncCloud() }
            withTimeout(5000) { model.state.first { !it.busy } }
            assertEquals(2, calls)
            assertEquals(1290L, repo.allEntries().single().amountMinor)
        } finally { release.complete(Unit); main { model.viewModelScope.cancel() }; db.close() }
    }

    @Test fun failureAndCancellationClearSyncIndicator() = runBlocking {
        for (cancel in listOf(false, true)) {
            val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val model = LedgerViewModel(LedgerRepository(db), syncAction = { _, _, _ ->
                entered.complete(Unit); release.await(); throw IllegalStateException("合成网络失败")
            })
            try {
                withTimeout(5000) { model.state.first { !it.loading } }
                main { model.syncCloud() }; withTimeout(5000) { entered.await() }
                assertTrue(model.state.value.syncing)
                if (cancel) main { model.viewModelScope.cancel() } else release.complete(Unit)
                withTimeout(5000) { model.state.first { !it.busy && !it.syncing } }
                if (!cancel) assertEquals("合成网络失败", model.state.value.message)
            } finally { release.complete(Unit); main { model.viewModelScope.cancel() }; db.close() }
        }
    }
}
