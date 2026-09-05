package com.familyledger.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.familyledger.app.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerDatabaseTest {
    private val entry = LedgerEntry("d02a0c82-5507-434c-b94b-08a01106e416", EntryType.EXPENSE,
        "2026-09-05", 6380, "食品酒水", account = "银行卡", member = "家人", recordedBy = "本人")

    @Test fun androidJsonCoercionCannotAcceptBrokenBackupFields() {
        listOf(org.json.JSONObject.NULL, 42, org.json.JSONObject()).forEach { value ->
            val root = org.json.JSONObject(BackupCodec.encode(listOf(entry)))
            root.getJSONArray("entries").getJSONObject(0).put("categoryL1", value)
            assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(root.toString()) }
        }
    }

    @Test fun editsPersistAcrossDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "persistence-${java.util.UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
        try {
            val repo = LedgerRepository(db)
            repo.save(entry)
            repo.save(entry.copy(amountMinor = 7200))
            db.close()
            db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
            val rows = LedgerRepository(db).entries.first()
            assertEquals(1, rows.size)
            assertEquals(7200L, rows.single().amountMinor)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun restoreIsIdempotentAndDoesNotResurrectDeletedRecords() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val repo = LedgerRepository(db)
            assertEquals(1, repo.restore(listOf(entry)))
            assertEquals(0, repo.restore(listOf(entry)))
            repo.delete(entry.id)
            assertTrue(repo.entries.first().isEmpty())
            assertEquals(0, repo.restore(listOf(entry)))
            assertTrue(repo.entries.first().isEmpty())
            assertNotNull(BackupCodec.decode(repo.backup()).single().deletedAt)
        } finally { db.close() }
    }

    @Test fun invalidRestoreDoesNotPartiallyWrite() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val repo = LedgerRepository(db)
            try {
                repo.restore(listOf(entry, entry.copy(id = "other", amountMinor = -1)))
                fail("Invalid batch must fail")
            } catch (_: IllegalArgumentException) { }
            assertTrue(repo.entries.first().isEmpty())
        } finally { db.close() }
    }
}
