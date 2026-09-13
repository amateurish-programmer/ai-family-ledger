package com.familyledger.app.data

import com.familyledger.app.domain.EntryType
import com.familyledger.app.domain.LedgerEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SyncEngineTest {
    private fun entry(n: Int) = LedgerEntry("00000000-0000-0000-0000-${n.toString().padStart(12, '0')}",
        EntryType.EXPENSE, "2026-09-06", 100, "测试分类", account = "测试账户", member = "测试成员",
        recordedBy = "测试成员", updatedAt = 1)
    private fun hash(row: LedgerEntry) = row.toString()

    private inner class Harness(rows: List<LedgerEntry> = emptyList()) {
        val cloud = rows.associate { it.id to SyncRemote(it, 1) }.toMutableMap()
        val local = mutableMapOf<String, LedgerEntry>()
        val indexes = mutableMapOf<String, SyncIndex>()
        var reads = 0
        var writes = 0
        var transactions = 0
        var commits = 0
        var failApply = false
        var failRemember = false
        var losePushResponse = false
        var beforePush: (() -> Unit)? = null
        var beforeFetch: (() -> Unit)? = null
        fun manifest() = cloud.values.sortedBy { it.entry.id }.map { SyncManifest(it.entry.id, it.revision, 128) }
        fun known(rows: List<LedgerEntry>) {
            rows.forEach { indexes[it.id] = SyncIndex(1, hash(it)); local[it.id] = it }
        }
        val engine = SyncEngine(::hash, { 256 }, fetch = { ids ->
            reads++
            beforeFetch?.invoke()
            ids.mapNotNull { cloud[it] }
        }, push = { rows ->
            writes++
            beforePush?.invoke()
            val results = rows.map { row ->
                val existing = cloud[row.entry.id]
                if ((existing?.revision ?: 0) == row.expected) {
                    val next = SyncRemote(row.entry, row.expected + 1)
                    cloud[row.entry.id] = next
                    SyncUploadResult(row.entry.id, next.revision)
                } else SyncUploadResult(row.entry.id, existing!!.revision, existing)
            }
            if (losePushResponse) error("synthetic response lost")
            results
        }, applyRemote = { rows ->
            if (failApply) error("synthetic Room failure")
            transactions++
            rows.forEach { local[it.id] = it }
        }, remember = { values ->
            if (failRemember) error("synthetic index failure")
            commits++
            indexes.putAll(values)
        })
        suspend fun run(rows: List<LedgerEntry> = local.values.toList()) = engine.run(rows, manifest(), indexes.toMap())
    }

    @Test fun unchangedThousandRowsNeedNoPayloadOrDiskWrites() = runBlocking {
        val rows = (1..1000).map(::entry)
        val h = Harness(rows).apply { known(rows) }
        val result = h.run()
        assertEquals(0, result.uploaded + result.downloaded)
        assertEquals(0, h.reads + h.writes + h.transactions + h.commits)
    }

    @Test fun firstUploadUsesTwentyBatchesForThousandRows() = runBlocking {
        val h = Harness()
        val result = h.run((1..1000).map(::entry))
        assertEquals(1000, result.uploaded)
        assertEquals(20, h.writes)
        assertEquals(20, h.commits)
        assertEquals(1000, h.indexes.size)
    }

    @Test fun firstDownloadUsesTwentyTransactionsAndIndexCommits() = runBlocking {
        val h = Harness((1..1000).map(::entry))
        assertEquals(1000, h.run().downloaded)
        assertEquals(20, h.reads)
        assertEquals(20, h.transactions)
        assertEquals(20, h.commits)
    }

    @Test fun missingIndexedRowFailsBeforeAnyUploadEvenWhenLocalWasLost() = runBlocking {
        val h = Harness().apply { indexes[entry(1).id] = SyncIndex(1, hash(entry(1))) }
        assertTrue(runCatching { h.run(listOf(entry(2))) }.isFailure)
        assertEquals(0, h.writes)
    }

    @Test fun revisionRollbackFailsBeforeAnyWrite() = runBlocking {
        val row = entry(1)
        val h = Harness(listOf(row)).apply { known(listOf(row)); indexes[row.id] = SyncIndex(2, hash(row)) }
        assertTrue(runCatching { h.run(listOf(row.copy(note = "本机修改"))) }.isFailure)
        assertEquals(0, h.writes)
    }

    @Test fun unknownSameIdDifferentContentsRequiresUserConflict() = runBlocking {
        val row = entry(1)
        val h = Harness(listOf(row))
        val result = h.run(listOf(row.copy(note = "另一个来源")))
        assertEquals(1, result.conflicts.size)
        assertEquals(0, h.writes + h.transactions + h.commits)
    }

    @Test fun concurrentEditKeepsConflictAndCommitsOtherSuccess() = runBlocking {
        val rows = listOf(entry(1), entry(2))
        val h = Harness(rows).apply {
            known(rows)
            beforePush = { cloud[rows[0].id] = SyncRemote(rows[0].copy(note = "云端修改"), 2) }
        }
        val result = h.run(rows.map { it.copy(note = "本机修改") })
        assertEquals(1, result.uploaded)
        assertEquals(1, result.conflicts.size)
        assertEquals(1L, h.indexes.getValue(rows[0].id).revision)
        assertEquals(2L, h.indexes.getValue(rows[1].id).revision)
    }

    @Test fun remoteDeletionDownloadsAndLocalDeletionUploadsAsTombstones() = runBlocking {
        val rows = listOf(entry(1), entry(2))
        val h = Harness(rows).apply {
            known(rows)
            cloud[rows[0].id] = SyncRemote(rows[0].copy(deletedAt = 2), 2)
        }
        val result = h.run(listOf(rows[0], rows[1].copy(deletedAt = 3)))
        assertEquals(1, result.uploaded)
        assertEquals(1, result.downloaded)
        assertEquals(2L, h.local.getValue(rows[0].id).deletedAt)
        assertEquals(3L, h.cloud.getValue(rows[1].id).entry.deletedAt)
    }

    @Test fun remoteDeletionWinsOverUneditedLocalRowWithoutSyncIndex() = runBlocking {
        val row = entry(1).copy(updatedAt = 1)
        val h = Harness(listOf(row)).apply {
            cloud[row.id] = SyncRemote(row.copy(deletedAt = 2), 2)
        }
        val result = h.run(listOf(row))
        assertTrue(result.conflicts.isEmpty())
        assertEquals(1, result.downloaded)
        assertEquals(2L, h.local.getValue(row.id).deletedAt)
    }

    @Test fun newerLocalEditStillConflictsWithRemoteDeletion() = runBlocking {
        val row = entry(1).copy(updatedAt = 3, note = "本机修改")
        val h = Harness(listOf(row)).apply {
            cloud[row.id] = SyncRemote(row.copy(updatedAt = 1, deletedAt = 2), 2)
        }
        val result = h.run(listOf(row))
        assertEquals(1, result.conflicts.size)
        assertEquals(0, result.downloaded)
        assertTrue(h.local.isEmpty())
    }

    @Test fun failedRoomTransactionNeverAdvancesIndexes() = runBlocking {
        val h = Harness(listOf(entry(1))).apply { failApply = true }
        assertTrue(runCatching { h.run() }.isFailure)
        assertTrue(h.indexes.isEmpty())
        h.failApply = false
        assertEquals(1, h.run().downloaded)
    }

    @Test fun failedIndexCommitRecoversByEqualContents() = runBlocking {
        val h = Harness(listOf(entry(1))).apply { failRemember = true }
        assertTrue(runCatching { h.run() }.isFailure)
        assertTrue(h.indexes.isEmpty())
        assertEquals(1, h.local.size)
        h.failRemember = false
        assertTrue(h.run().conflicts.isEmpty())
        assertEquals(1, h.indexes.size)
    }

    @Test fun lostSuccessfulUploadResponseRecoversWithoutDuplicateOrConflict() = runBlocking {
        val row = entry(1)
        val h = Harness().apply { losePushResponse = true }
        assertTrue(runCatching { h.run(listOf(row)) }.isFailure)
        assertTrue(h.indexes.isEmpty())
        h.losePushResponse = false
        assertTrue(h.run(listOf(row)).conflicts.isEmpty())
        assertEquals(1, h.cloud.size)
        assertEquals(1L, h.indexes.getValue(row.id).revision)
        assertEquals(1, h.writes)
    }

    @Test fun disappearanceBetweenManifestAndFetchFailsClosed() = runBlocking {
        val h = Harness(listOf(entry(1))).apply { beforeFetch = { cloud.clear() } }
        assertTrue(runCatching { h.run(listOf(entry(2))) }.isFailure)
        assertEquals(0, h.writes)
        assertTrue(h.indexes.isEmpty())
    }

    @Test fun byteBudgetBoundsBatchesAndRejectsOversizedSingleton() {
        assertEquals(listOf(2, 2, 1), SyncEngine.batches(listOf(4, 4, 4, 4, 4), 50, 10) { it }.map { it.size })
        assertThrows(IllegalArgumentException::class.java) { SyncEngine.batches(listOf(11), 50, 10) { it } }
    }

    @Test fun duplicateManifestAndPayloadIdsAreRejected() = runBlocking {
        val row = entry(1)
        val h = Harness(listOf(row))
        assertTrue(runCatching { h.engine.run(emptyList(), h.manifest() + h.manifest(), emptyMap()) }.isFailure)
        assertEquals(0, h.reads + h.writes)
    }
}
