package com.familyledger.app.data

import com.familyledger.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SyncFingerprintTest {
    private val original = LedgerEntry("00000000-0000-0000-0000-000000000001", EntryType.EXPENSE,
        "2026-09-06", 100, "测试分类", account = "测试账户", member = "测试成员", recordedBy = "测试成员", updatedAt = 1)
    // SHA-256 of the exact v1.1 sorted-key JSON fixture (before gift fields existed).
    private val legacyHash = "43636a1a3699bc11e674c3d8cad67afce996868eb5134ab6d18752c9c1e93c09"
    @Test fun defaultFieldsKeepLegacyHashButGiftChangesAndClearingAreDetected() {
        assertEquals(legacyHash, SyncFingerprint.hash(original))
        val gift = original.copy(isGift = true, counterparty = "亲友甲")
        assertNotEquals(legacyHash, SyncFingerprint.hash(gift))
        assertNotEquals(SyncFingerprint.hash(gift), SyncFingerprint.hash(gift.copy(counterparty = "亲友乙")))
        assertEquals(legacyHash, SyncFingerprint.hash(gift.copy(isGift = false, counterparty = "")))
    }
    @Test fun upgradingUnchangedLocalRowsDownloadsRemoteEditsWithoutFalseConflict() = runBlocking {
        val remote = original.copy(note = "另一台手机修改", updatedAt = 2)
        var applied: List<LedgerEntry> = emptyList()
        val engine = SyncEngine(SyncFingerprint::hash, { 256 },
            fetch = { listOf(SyncRemote(remote, 2)) },
            push = { error("unchanged upgraded row must not upload") },
            applyRemote = { applied = it }, remember = {})
        val result = engine.run(listOf(original), listOf(SyncManifest(original.id, 2, 1024)),
            mapOf(original.id to SyncIndex(1, legacyHash)))
        assertTrue(result.conflicts.isEmpty()); assertEquals(0, result.uploaded)
        assertEquals(listOf(remote), applied)
    }
    @Test fun upgradingUnchangedCloudRevisionRequiresNoNetworkPayload() = runBlocking {
        val engine = SyncEngine(SyncFingerprint::hash, { 256 }, fetch = { error("unexpected fetch") },
            push = { error("unexpected upload") }, applyRemote = { error("unexpected write") }, remember = { error("unexpected index") })
        val result = engine.run(listOf(original), listOf(SyncManifest(original.id, 1, 1024)), mapOf(original.id to SyncIndex(1, legacyHash)))
        assertEquals(0, result.downloaded + result.uploaded); assertTrue(result.conflicts.isEmpty())
    }
}
