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

    @Test fun migrationFromV3PreservesLedgerAndChatAndDefaultsGiftMetadata() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "gift-migration-${java.util.UUID.randomUUID()}.db"
        context.openOrCreateDatabase(name, android.content.Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("CREATE TABLE entries (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, occurredOn TEXT NOT NULL, amountMinor INTEGER NOT NULL, categoryL1 TEXT NOT NULL, categoryL2 TEXT NOT NULL, account TEXT NOT NULL, member TEXT NOT NULL, recordedBy TEXT NOT NULL, merchant TEXT NOT NULL, project TEXT NOT NULL, note TEXT NOT NULL, currency TEXT NOT NULL, updatedAt INTEGER NOT NULL, deletedAt INTEGER, importDataJson TEXT NOT NULL DEFAULT '')")
            old.execSQL("CREATE INDEX index_entries_occurredOn ON entries(occurredOn)")
            old.execSQL("INSERT INTO entries VALUES ('old','EXPENSE','2026-09-06',3680,'食品','','账户','家人','本人','','','原账目','CNY',1,NULL,'')")
            old.execSQL("CREATE TABLE chat_messages (sequence INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, owner TEXT NOT NULL, messageId TEXT NOT NULL, user INTEGER NOT NULL, text TEXT NOT NULL, context TEXT NOT NULL)")
            old.execSQL("CREATE UNIQUE INDEX index_chat_messages_owner_messageId ON chat_messages(owner,messageId)")
            old.execSQL("CREATE TABLE chat_drafts (owner TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL)")
            old.execSQL("INSERT INTO chat_messages(owner,messageId,user,text,context) VALUES ('a','old-chat',0,'原对话','')")
            old.version = 3
        }
        val db = Room.databaseBuilder(context, LedgerDatabase::class.java, name)
            .addMigrations(LedgerDatabase.MIGRATION_3_4).build()
        try {
            val repo = LedgerRepository(db)
            val row = repo.allEntries().single()
            assertFalse(row.isGift); assertEquals("", row.counterparty)
            assertEquals(3680L, row.amountMinor); assertEquals("原账目", row.note)
            assertEquals("原对话", repo.chatMessages("a").single().text)
            repo.save(row.copy(isGift = true, counterparty = "测试亲友"))
            assertTrue(repo.allEntries().single().isGift)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun reimportingLegacyWorkbookKeepsConfirmedGiftMetadata() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val repo = LedgerRepository(db)
            val bytes = SampleWorkbooks.book()
            val original = repo.previewImport(bytes, "legacy.xlsx").rows.single().entry!!
            assertEquals(1, repo.commitImport(listOf(original)))
            val saved = repo.allEntries().single()
            assertEquals(1, repo.confirmGiftHistory(listOf(saved), mapOf(saved.id to "测试亲友")))
            assertEquals(0, repo.commitImport(listOf(original)))
            val retained = repo.allEntries().single()
            assertTrue(retained.isGift); assertEquals("测试亲友", retained.counterparty)
            assertEquals(original.amountMinor, retained.amountMinor)
            assertEquals(ImportStatus.EXISTING, repo.previewImport(bytes, "renamed.xlsx").rows.single().status)
        } finally { db.close() }
    }

    @Test fun giftConfirmationIsAtomicAndDoesNotChangeFinancialFields() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val repo = LedgerRepository(db)
            repo.restore(listOf(entry, entry.copy(id = "second")))
            val originals = repo.allEntries()
            assertEquals(2, repo.confirmGiftHistory(originals, originals.associate { it.id to " 测试亲友 " }))
            val after = repo.allEntries()
            originals.forEach { original ->
                val actual = after.single { it.id == original.id }
                assertEquals(original.copy(isGift = true, counterparty = "测试亲友", updatedAt = actual.updatedAt), actual)
            }
            assertTrue(runCatching { repo.confirmGiftHistory(originals, originals.associate { it.id to "覆盖" }) }.isFailure)
            assertEquals(after, repo.allEntries())
            assertTrue(runCatching { repo.confirmGiftHistory(after, after.associate { it.id to if (it.id == "second") "x".repeat(101) else "新亲友" }) }.isFailure)
            assertEquals(after, repo.allEntries())
            assertEquals(0, repo.restore(originals))
            assertEquals(after, repo.allEntries())
            repo.delete("second")
            val deleted = repo.allEntries()
            assertTrue(runCatching { repo.confirmGiftHistory(after, after.associate { it.id to "覆盖" }) }.isFailure)
            assertEquals(deleted, repo.allEntries())
        } finally { db.close() }
    }

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
            repo.save(entry.copy(amountMinor = 7200, isGift = true, counterparty = "测试亲友"))
            db.close()
            db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
            val rows = LedgerRepository(db).entries.first()
            assertEquals(1, rows.size)
            assertEquals(7200L, rows.single().amountMinor)
            assertTrue(rows.single().isGift)
            assertEquals("测试亲友", rows.single().counterparty)
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
