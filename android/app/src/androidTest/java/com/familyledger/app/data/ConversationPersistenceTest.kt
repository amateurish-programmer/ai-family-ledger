package com.familyledger.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.viewModelScope
import com.familyledger.app.ui.LedgerViewModel
import com.familyledger.app.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ConversationPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val draft = LedgerEntry("00000000-0000-0000-0000-000000000001", EntryType.EXPENSE,
        "2026-09-06", 3680, "食品", account = "测试账户", member = "家人", recordedBy = "本人", updatedAt = 1)
    private fun open(name: String) = Room.databaseBuilder(context, LedgerDatabase::class.java, name)
        .addMigrations(LedgerDatabase.MIGRATION_1_2, LedgerDatabase.MIGRATION_2_3).build()

    @Test fun logoutKeepsLoadedHistoryAndPendingProposalsVisible() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val cloud = CloudService(context)
        val owner = cloud.conversationOwner()
        val repo = LedgerRepository(db)
        val saved = ChatMessage("logout-fixture", false, "这笔记录尚未确认")
        repo.appendChat(owner, saved, listOf(draft))
        lateinit var model: LedgerViewModel
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { model = LedgerViewModel(repo, cloud) }
        try {
            withTimeout(5000) { model.state.first { !it.loading } }
            instrumentation.runOnMainSync { model.logout() }
            withTimeout(5000) { model.state.first { it.message == "已退出登录，对话与本机账本保留" } }
            assertEquals(listOf(saved), model.state.value.chatMessages)
            assertEquals(listOf(draft), model.state.value.quickDrafts)
            assertEquals(listOf(saved), repo.chatMessages(owner))
        } finally {
            instrumentation.runOnMainSync { model.viewModelScope.cancel() }
            db.close()
        }
    }

    @Test fun historyAndPendingDraftsSurviveReopenWithoutCrossAccountMixing() = runBlocking {
        val name = "chat-${java.util.UUID.randomUUID()}.db"
        var db = open(name)
        try {
            var repo = LedgerRepository(db)
            repo.appendChat("account-a", ChatMessage("first", true, "午饭36.8"), emptyList())
            repo.appendChat("account-a", ChatMessage("second", false, "待确认"), listOf(draft))
            repo.appendChat("account-b", ChatMessage("other", true, "另一个账号"), emptyList())
            db.close(); db = open(name); repo = LedgerRepository(db)
            assertEquals(listOf("first", "second"), repo.chatMessages("account-a").map { it.id })
            assertEquals(listOf("other"), repo.chatMessages("account-b").map { it.id })
            assertTrue(repo.chatMessages("local").isEmpty())
            assertEquals(listOf(draft), repo.chatDrafts("account-a"))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun confirmingDraftsClearsPendingStateAtomicallyAndCannotRepeat() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val repo = LedgerRepository(db)
            repo.saveChatDrafts("a", listOf(draft))
            repo.confirmChatDrafts("a", listOf(draft))
            assertTrue(repo.chatDrafts("a").isEmpty())
            assertEquals(1, repo.allEntries().size)
            assertTrue(repo.chatMessages("a").single().text.contains("已保存 1 笔"))
            assertTrue(runCatching { repo.confirmChatDrafts("a", listOf(draft)) }.isFailure)
            assertEquals(1, repo.allEntries().size)
        } finally { db.close() }
    }

    @Test fun confirmationMessageFailureRollsBackLedgerAndKeepsDrafts() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val repo = LedgerRepository(db)
            repo.saveChatDrafts("a", listOf(draft))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_chat BEFORE INSERT ON chat_messages BEGIN SELECT RAISE(ABORT, 'synthetic message failure'); END")
            assertTrue(runCatching { repo.confirmChatDrafts("a", listOf(draft)) }.isFailure)
            assertTrue(repo.allEntries().isEmpty())
            assertEquals(listOf(draft), repo.chatDrafts("a"))
            assertTrue(repo.chatMessages("a").isEmpty())
        } finally { db.close() }
    }

    @Test fun staleDraftConfirmationDoesNotSaveAnything() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val repo = LedgerRepository(db)
            repo.saveChatDrafts("a", listOf(draft))
            assertTrue(runCatching { repo.confirmChatDrafts("a", listOf(draft.copy(amountMinor = 5000))) }.isFailure)
            assertTrue(repo.allEntries().isEmpty()); assertEquals(listOf(draft), repo.chatDrafts("a"))
        } finally { db.close() }
    }

    @Test fun messageRetryIsIdempotentAndInvalidDraftRollsBackMessage() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val repo = LedgerRepository(db)
            val message = ChatMessage("same", false, "测试")
            repo.appendChat("a", message, emptyList()); repo.appendChat("a", message, emptyList())
            assertEquals(1, repo.chatMessages("a").size)
            assertTrue(runCatching { repo.appendChat("a", message.copy(id = "bad"), listOf(draft.copy(amountMinor = -1))) }.isFailure)
            assertEquals(1, repo.chatMessages("a").size)
        } finally { db.close() }
    }

    @Test fun migrationFromV2PreservesLedgerAndCreatesConversationTables() = runBlocking {
        val name = "migration-${java.util.UUID.randomUUID()}.db"
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("CREATE TABLE entries (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, occurredOn TEXT NOT NULL, amountMinor INTEGER NOT NULL, categoryL1 TEXT NOT NULL, categoryL2 TEXT NOT NULL, account TEXT NOT NULL, member TEXT NOT NULL, recordedBy TEXT NOT NULL, merchant TEXT NOT NULL, project TEXT NOT NULL, note TEXT NOT NULL, currency TEXT NOT NULL, updatedAt INTEGER NOT NULL, deletedAt INTEGER, importDataJson TEXT NOT NULL DEFAULT '')")
            old.execSQL("CREATE INDEX index_entries_occurredOn ON entries(occurredOn)")
            old.execSQL("INSERT INTO entries VALUES ('old','EXPENSE','2026-09-06',3680,'食品','','账户','家人','本人','','','原账目','CNY',1,NULL,'')")
            old.version = 2
        }
        val db = open(name)
        try {
            val repo = LedgerRepository(db)
            assertEquals(3680L, repo.allEntries().single().amountMinor)
            assertEquals("原账目", repo.allEntries().single().note)
            repo.appendChat("a", ChatMessage("new", true, "迁移后对话"), emptyList())
            assertEquals("迁移后对话", repo.chatMessages("a").single().text)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
