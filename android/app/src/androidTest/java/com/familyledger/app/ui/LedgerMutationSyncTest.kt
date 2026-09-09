package com.familyledger.app.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.familyledger.app.data.*
import com.familyledger.app.domain.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LedgerMutationSyncTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun entry(id: String = "00000000-0000-0000-0000-000000000001") = LedgerEntry(id,
        EntryType.EXPENSE, "2026-09-09", 1280, "食品", account = "现金", member = "家人", recordedBy = "本人")

    @Test fun confirmedDraftEditAndDeleteEachSyncOnceAfterLocalCommit() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val repo = LedgerRepository(db)
        val draft = entry()
        repo.saveChatDrafts("local", listOf(draft))
        val snapshots = mutableListOf<List<LedgerEntry>>()
        val model = LedgerViewModel(repo,
            syncAction = { rows, _, _ -> snapshots += rows; SyncResult(1, 0, emptyList()) },
            mutationSyncEligible = { true })
        try {
            withTimeout(5000) { model.state.first { !it.loading && it.quickDrafts == listOf(draft) } }
            main { model.saveQuickDrafts() }
            withTimeout(5000) { model.state.first { !it.busy && snapshots.size == 1 } }
            assertEquals(listOf(draft.id), snapshots.single().map { it.id })
            assertEquals("上传 1 条", model.state.value.message)

            main { model.save(draft.copy(amountMinor = 2560)) }
            withTimeout(5000) { model.state.first { !it.busy && snapshots.size == 2 } }
            assertEquals(2560L, snapshots.last().single().amountMinor)

            main { model.delete(draft.id) }
            withTimeout(5000) { model.state.first { !it.busy && snapshots.size == 3 } }
            assertNotNull(snapshots.last().single().deletedAt)
        } finally { main { model.viewModelScope.cancel() }; db.close() }
    }

    @Test fun zeroChangeManualSyncIsSilentAndMutationFailureKeepsLocalData() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val repo = LedgerRepository(db)
        var fail = false
        val model = LedgerViewModel(repo,
            syncAction = { _, _, _ -> if (fail) error("合成网络失败") else SyncResult(0, 0, emptyList()) },
            mutationSyncEligible = { true })
        try {
            withTimeout(5000) { model.state.first { !it.loading } }
            main { model.syncCloud() }
            withTimeout(5000) { model.state.first { !it.busy } }
            assertNull(model.state.value.message)

            fail = true
            val row = entry()
            main { model.save(row) }
            withTimeout(5000) { model.state.first { !it.busy } }
            assertEquals(row.amountMinor, repo.allEntries().single().amountMinor)
            assertEquals("已保存在本机，自动同步失败：合成网络失败", model.state.value.message)
        } finally { main { model.viewModelScope.cancel() }; db.close() }
    }

    @Test fun importRollbackAndRestoreEachSyncAfterCommittedChanges() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val repo = LedgerRepository(db)
        var syncCalls = 0
        val model = LedgerViewModel(repo,
            syncAction = { _, _, _ -> syncCalls++; SyncResult(1, 0, emptyList()) },
            mutationSyncEligible = { true })
        val spreadsheet = File.createTempFile("mutation-import-", ".xlsx", context.cacheDir)
        val backup = File.createTempFile("mutation-restore-", ".json", context.cacheDir)
        try {
            withTimeout(5000) { model.state.first { !it.loading } }
            spreadsheet.writeBytes(XlsxCodec.write(listOf(entry())))
            main { model.previewImport(context.contentResolver, Uri.fromFile(spreadsheet)) }
            withTimeout(5000) { model.state.first { it.importPreview != null && !it.busy } }
            main { model.confirmImport() }
            withTimeout(5000) { model.state.first { !it.busy && syncCalls == 1 } }
            val batchId = model.state.value.batches.single().id

            main { model.rollbackBatch(batchId) }
            withTimeout(5000) { model.state.first { !it.busy && syncCalls == 2 } }
            assertNotNull(repo.allEntries().single().deletedAt)

            val restored = entry("00000000-0000-0000-0000-000000000002")
            backup.writeText(BackupCodec.encode(listOf(restored)), Charsets.UTF_8)
            main { model.previewRestore(context.contentResolver, Uri.fromFile(backup)) }
            withTimeout(5000) { model.state.first { it.restorePreview != null && !it.busy } }
            main { model.confirmRestore() }
            withTimeout(5000) { model.state.first { !it.busy && syncCalls == 3 } }
            assertEquals(restored.amountMinor, repo.allEntries().single { it.id == restored.id }.amountMinor)
        } finally {
            main { model.viewModelScope.cancel() }; db.close(); spreadsheet.delete(); backup.delete()
        }
    }
}
