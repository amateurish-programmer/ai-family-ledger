package com.familyledger.app.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.viewModelScope
import com.familyledger.app.domain.*
import com.familyledger.app.ui.LedgerViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DocumentOperationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val entry = LedgerEntry("d02a0c82-5507-434c-b94b-08a01106e416", EntryType.EXPENSE,
        "2024-01-01", 6380, "食品", account = "银行卡", member = "家人", recordedBy = "本人")

    @Test fun exportCallbackWaitsForBusyOperationAndIncludesAllHistoricalRows() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val repo = LedgerRepository(db)
        val income = entry.copy(id = "d02a0c82-5507-434c-b94b-08a01106e417", type = EntryType.INCOME, occurredOn = "2026-09-06", amountMinor = 99900)
        val adjustment = entry.copy(id = "d02a0c82-5507-434c-b94b-08a01106e418", type = EntryType.BALANCE_ADJUSTMENT, occurredOn = "2025-02-01", amountMinor = -100)
        val deleted = entry.copy(id = "d02a0c82-5507-434c-b94b-08a01106e419", deletedAt = 1)
        repo.restore(listOf(entry, income, adjustment, deleted))
        lateinit var model: LedgerViewModel
        instrumentation.runOnMainSync { model = LedgerViewModel(repo) }
        val target = File.createTempFile("ledger-export-", ".xlsx", context.cacheDir)
        val locked = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var holder: Job? = null
        try {
            withTimeout(5000) { model.state.first { !it.loading } }
            holder = launch(Dispatchers.IO) { db.withTransaction { locked.complete(Unit); release.await() } }
            locked.await()
            instrumentation.runOnMainSync { model.save(entry.copy(amountMinor = 7200)) }
            assertTrue(model.state.value.busy)
            instrumentation.runOnMainSync { model.exportSpreadsheet(context.contentResolver, Uri.fromFile(target)) }
            release.complete(Unit); holder.join()
            withTimeout(10000) { model.state.first { it.message?.startsWith("Excel 已导出") == true } }
            assertTrue("Created document must contain a complete workbook", target.length() > 0)
            val preview = SpreadsheetImport.preview(target.readBytes(), "test.xlsx", emptyList())
            assertEquals(3, preview.rows.size)
            assertTrue(preview.rows.all { it.status != ImportStatus.ERROR })
            assertEquals(setOf("2024-01-01", "2025-02-01", "2026-09-06"), preview.rows.map { it.entry!!.occurredOn }.toSet())
            assertEquals(7200L, preview.rows.first { it.entry!!.type == EntryType.EXPENSE }.entry!!.amountMinor)
        } finally {
            release.complete(Unit); holder?.join()
            instrumentation.runOnMainSync { model.viewModelScope.cancel() }
            db.close(); target.delete()
        }
    }

    @Test fun importCallbackWaitsForBusyOperationInsteadOfDisappearing() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val repo = LedgerRepository(db)
        lateinit var model: LedgerViewModel
        instrumentation.runOnMainSync { model = LedgerViewModel(repo) }
        val source = File.createTempFile("ledger-import-", ".xlsx", context.cacheDir)
        source.writeBytes(XlsxCodec.write(listOf(entry)))
        val locked = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var holder: Job? = null
        try {
            withTimeout(5000) { model.state.first { !it.loading } }
            holder = launch(Dispatchers.IO) { db.withTransaction { locked.complete(Unit); release.await() } }
            locked.await()
            instrumentation.runOnMainSync { model.save(entry.copy(id = "d02a0c82-5507-434c-b94b-08a01106e420", occurredOn = "2026-09-05")) }
            assertTrue(model.state.value.busy)
            instrumentation.runOnMainSync { model.previewImport(context.contentResolver, Uri.fromFile(source)) }
            release.complete(Unit); holder.join()
            val state = withTimeout(10000) { model.state.first { it.importPreview != null } }
            assertEquals(1, state.importPreview!!.rows.size)
            assertEquals(1, state.selectedImportKeys.size)
        } finally {
            release.complete(Unit); holder?.join()
            instrumentation.runOnMainSync { model.viewModelScope.cancel() }
            db.close(); source.delete()
        }
    }
}
