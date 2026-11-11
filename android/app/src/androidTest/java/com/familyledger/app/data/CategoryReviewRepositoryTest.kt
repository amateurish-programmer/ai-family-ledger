package com.familyledger.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.familyledger.app.domain.EntryType
import com.familyledger.app.domain.ImportOrigin
import com.familyledger.app.domain.LedgerEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryReviewRepositoryTest {
    private lateinit var database: LedgerDatabase
    private lateinit var repository: LedgerRepository
    private val original = LedgerEntry(
        id = "category-first", type = EntryType.EXPENSE, occurredOn = "2026-11-10",
        amountMinor = 15678, categoryL1 = "行车交通", categoryL2 = "加油",
        account = "银行卡", member = "归属家人", recordedBy = "实际操作者",
        merchant = "合成加油站", project = "家庭出行", note = "合成历史记录",
        updatedAt = 100, isGift = true, counterparty = "合成亲友",
        origin = ImportOrigin("a".repeat(64), "synthetic.xlsx", "支出", 2, 50,
            "2026/11/10", "原账户", "原项目", mapOf("原分类" to "行车交通", "金额" to "156.78"))
    )

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).build()
        repository = LedgerRepository(database)
    }

    @After fun close() { database.close() }

    private fun replacement(row: LedgerEntry) = row.copy(categoryL1 = "私家车", categoryL2 = "油费")

    @Test fun confirmedCategoriesPreserveAmountOriginAndAllOtherMetadata() = runBlocking {
        repository.restore(listOf(original))
        val snapshot = repository.allEntries().single()
        val before = System.currentTimeMillis()
        assertEquals(1, repository.confirmCategoryChanges(listOf(snapshot), listOf(replacement(snapshot))))
        val actual = repository.allEntries().single()
        assertEquals("私家车", actual.categoryL1)
        assertEquals("油费", actual.categoryL2)
        assertEquals(15678L, actual.amountMinor)
        assertEquals(original.origin, actual.origin)
        assertEquals(snapshot.copy(categoryL1 = "私家车", categoryL2 = "油费", updatedAt = actual.updatedAt), actual)
        assertTrue(actual.updatedAt >= before)
    }

    @Test fun changedRecordRejectsWholeBatchWithoutOverwritingConcurrentEdit() = runBlocking {
        repository.restore(listOf(original, original.copy(id = "category-second")))
        val snapshots = repository.allEntries()
        repository.save(snapshots.last().copy(amountMinor = 23456, note = "预览后修改"))
        val beforeConfirmation = repository.allEntries()
        assertTrue(runCatching {
            repository.confirmCategoryChanges(snapshots, snapshots.map(::replacement))
        }.isFailure)
        assertEquals(beforeConfirmation, repository.allEntries())
        assertTrue(repository.allEntries().all { it.categoryL1 == "行车交通" })
    }

    @Test fun deletedRecordRejectsWholeBatchAndCannotBeResurrected() = runBlocking {
        repository.restore(listOf(original, original.copy(id = "category-second")))
        val snapshots = repository.allEntries()
        repository.delete("category-second")
        val tombstones = repository.allEntries()
        assertTrue(runCatching {
            repository.confirmCategoryChanges(snapshots, snapshots.map(::replacement))
        }.isFailure)
        assertEquals(tombstones, repository.allEntries())
        assertNotNull(repository.allEntries().single { it.id == "category-second" }.deletedAt)
    }

    @Test fun replayedConfirmationCannotOverwriteNewerCategories() = runBlocking {
        repository.restore(listOf(original))
        val snapshots = repository.allEntries()
        val replacements = snapshots.map(::replacement)
        assertEquals(1, repository.confirmCategoryChanges(snapshots, replacements))
        val confirmed = repository.allEntries()
        assertTrue(runCatching { repository.confirmCategoryChanges(snapshots, replacements) }.isFailure)
        assertEquals(confirmed, repository.allEntries())
    }

    @Test fun invalidOrNonCategoryReplacementsRejectWholeBatch() = runBlocking {
        repository.restore(listOf(original, original.copy(id = "category-second")))
        val snapshots = repository.allEntries()
        val first = replacement(snapshots.first())
        val second = replacement(snapshots.last())
        val invalidRows = listOf(
            second.copy(amountMinor = 1), second.copy(member = "其他人"),
            second.copy(recordedBy = "其他操作者"), second.copy(note = "覆盖备注"),
            second.copy(origin = null), second.copy(isGift = false),
            second.copy(counterparty = "其他亲友"), second.copy(updatedAt = 999),
            second.copy(deletedAt = 1000), second.copy(categoryL1 = ""),
            second.copy(categoryL2 = "x".repeat(101)), second.copy(id = "missing")
        )
        invalidRows.forEach { invalid ->
            assertTrue(runCatching { repository.confirmCategoryChanges(snapshots, listOf(first, invalid)) }.isFailure)
            assertEquals(snapshots, repository.allEntries())
        }
        listOf(emptyList(), listOf(first), listOf(first, first), snapshots).forEach { invalid ->
            assertTrue(runCatching { repository.confirmCategoryChanges(snapshots, invalid) }.isFailure)
            assertEquals(snapshots, repository.allEntries())
        }
        assertTrue(runCatching { repository.confirmCategoryChanges(emptyList(), emptyList()) }.isFailure)
        assertTrue(runCatching {
            repository.confirmCategoryChanges(listOf(snapshots.first(), snapshots.first()), listOf(first, first))
        }.isFailure)
    }

    @Test fun blankRecordIdsCannotBeUsedForConfirmation() = runBlocking {
        repository.restore(listOf(original.copy(id = "")))
        val snapshots = repository.allEntries()
        assertTrue(runCatching {
            repository.confirmCategoryChanges(snapshots, snapshots.map(::replacement))
        }.isFailure)
        assertEquals(snapshots, repository.allEntries())
    }
}
