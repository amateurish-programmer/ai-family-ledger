package com.familyledger.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.familyledger.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IncrementalImportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun oldExportPlusNewTimeCommitsOnlyNewSelectionAndReimportIsIdempotent() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val repo = LedgerRepository(db)
            val oldBytes = SampleWorkbooks.book()
            val old = repo.previewImport(oldBytes, "old.xlsx").rows.single().entry!!
            assertEquals(1, repo.commitImport(listOf(old)))
            val later = SampleWorkbooks.expense.toMutableList().apply { this[1] = "2026-09-06 18:30:01" }
            val bytes = SampleWorkbooks.book(listOf(SampleWorkbooks.headers, SampleWorkbooks.expense, later))
            val preview = repo.previewImport(bytes, "new.xlsx")
            assertEquals(listOf(ImportStatus.SUSPECTED, ImportStatus.NEW), preview.rows.map { it.status })
            val selected = preview.rows.filter { it.status == ImportStatus.NEW }.mapNotNull { it.entry }
            assertEquals(1, repo.commitImport(selected))
            assertEquals(7360L, repo.allEntries().sumOf { it.amountMinor })
            assertEquals(0, repo.commitImport(selected))
            assertEquals(listOf(ImportStatus.SUSPECTED, ImportStatus.EXISTING), repo.previewImport(bytes, "renamed.xlsx").rows.map { it.status })
            assertEquals(ImportStatus.EXISTING, repo.previewImport(oldBytes, "old.xlsx").rows.single().status)
            assertEquals(2, repo.allEntries().size)
        } finally { db.close() }
    }

    @Test fun identicalRealTransactionsRequireExplicitConsentAndCanBothBeRetained() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val repo = LedgerRepository(db)
            val preview = repo.previewImport(SampleWorkbooks.book(listOf(SampleWorkbooks.headers, SampleWorkbooks.expense, SampleWorkbooks.expense)), "twins.xlsx")
            val rows = preview.rows.mapNotNull { it.entry }
            assertEquals(listOf(ImportStatus.NEW, ImportStatus.SUSPECTED), preview.rows.map { it.status })
            try {
                repo.commitImport(rows)
                fail("A suspected duplicate needs explicit consent")
            } catch (_: IllegalArgumentException) { }
            assertTrue(repo.allEntries().isEmpty())
            assertEquals(2, repo.commitImport(rows, setOf(rows[1].id)))
            assertEquals(7360L, repo.allEntries().sumOf { it.amountMinor })
        } finally { db.close() }
    }

    @Test fun duplicateArrivingAfterPreviewRejectsWholeSelection() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val repo = LedgerRepository(db)
            val later = SampleWorkbooks.expense.toMutableList().apply { this[1] = "2026-09-06 18:30:01" }
            val rows = repo.previewImport(SampleWorkbooks.book(listOf(SampleWorkbooks.headers, SampleWorkbooks.expense, later)), "pending.xlsx").rows.mapNotNull { it.entry }
            val arriving = rows[1].copy(id = "64a77cd4-7915-4353-ad4f-b389db5b3807")
            repo.save(arriving)
            try {
                repo.commitImport(rows)
                fail("A newly arrived duplicate must require a fresh preview")
            } catch (_: IllegalArgumentException) { }
            assertEquals(listOf(arriving.id), repo.allEntries().map { it.id })
        } finally { db.close() }
    }

    @Test fun localEditsAndDeletionsRemainProtectedWhenSourceIsRegenerated() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val repo = LedgerRepository(db)
            val old = repo.previewImport(SampleWorkbooks.book(), "old.xlsx").rows.single().entry!!
            repo.commitImport(listOf(old))
            repo.save(old.copy(amountMinor = 5000, note = "本地修正", occurredOn = "2026-09-07"))
            repo.delete(old.id)
            val regenerated = repo.previewImport(SampleWorkbooks.book(suffix = "regenerated"), "new.xlsx").rows.single()
            assertEquals(ImportStatus.SUSPECTED, regenerated.status)
            try {
                repo.commitImport(listOf(regenerated.entry!!))
                fail("The original source of an edited tombstone still needs consent")
            } catch (_: IllegalArgumentException) { }
            val stored = repo.allEntries().single()
            assertEquals(old.id, stored.id)
            assertEquals(5000L, stored.amountMinor)
            assertNotNull(stored.deletedAt)
        } finally { db.close() }
    }

    @Test fun changedOrRemovedSourceRowsDoNotUpdateOrDeleteExistingLedger() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val repo = LedgerRepository(db)
            val old = repo.previewImport(SampleWorkbooks.book(), "old.xlsx").rows.single().entry!!
            repo.commitImport(listOf(old))
            val changed = SampleWorkbooks.expense.toMutableList().apply { this[6] = "40.00" }
            val candidate = repo.previewImport(SampleWorkbooks.book(listOf(SampleWorkbooks.headers, changed)), "changed.xlsx").rows.single()
            assertEquals(ImportStatus.NEW, candidate.status)
            assertEquals(1, repo.commitImport(listOf(candidate.entry!!)))
            val afterChange = repo.allEntries()
            assertEquals(3680L, afterChange.single { it.id == old.id }.amountMinor)
            assertEquals(2, afterChange.size)
            val removed = repo.previewImport(SampleWorkbooks.book(listOf(SampleWorkbooks.headers)), "removed.xlsx")
            assertTrue(removed.rows.isEmpty())
            assertEquals(0, repo.commitImport(emptyList()))
            assertEquals(2, repo.allEntries().count { it.deletedAt == null })
        } finally { db.close() }
    }
}
