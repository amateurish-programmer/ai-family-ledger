package com.familyledger.app.data

import com.familyledger.app.domain.LedgerEntry

internal data class SyncIndex(val revision: Long, val hash: String)
internal data class SyncManifest(val id: String, val revision: Long, val payloadBytes: Int)
internal data class SyncRemote(val entry: LedgerEntry, val revision: Long)
internal data class SyncUpload(val entry: LedgerEntry, val expected: Long)
internal data class SyncUploadResult(val id: String, val revision: Long, val conflict: SyncRemote? = null)

/** Transport-independent merge. Local writes must remain locked for the entire run. */
internal class SyncEngine(
    private val hash: (LedgerEntry) -> String,
    private val uploadBytes: (SyncUpload) -> Int,
    private val fetch: suspend (List<String>) -> List<SyncRemote>,
    private val push: suspend (List<SyncUpload>) -> List<SyncUploadResult>,
    private val applyRemote: suspend (List<LedgerEntry>) -> Unit,
    private val remember: suspend (Map<String, SyncIndex>) -> Unit,
    private val progress: (String) -> Unit = {},
) {
    suspend fun run(local: List<LedgerEntry>, manifest: List<SyncManifest>, indexes: Map<String, SyncIndex>): SyncResult {
        val own = local.associateBy { it.id }
        require(own.size == local.size) { "本机存在重复 ID，未同步" }
        val remoteIds = manifest.map { it.id }.toSet()
        require(remoteIds.size == manifest.size) { "云端分页含重复记录" }
        require(indexes.keys.all { it in remoteIds }) { "云端缺失已同步记录，请停止同步并核查服务端备份，未自动重建" }
        require(manifest.size <= 100000 && manifest.sumOf { it.payloadBytes.toLong() } <= 10 * 1024 * 1024) { "云端账本超过本版本 10 MB / 100000 条同步上限" }
        manifest.forEach { row ->
            require(row.revision > 0 && row.payloadBytes in 1..786432) { "云端清单无效" }
            require(row.revision >= (indexes[row.id]?.revision ?: 0)) { "云端版本低于已同步版本，请停止同步并核查服务端恢复情况" }
        }
        val uploads = mutableListOf<SyncUpload>()
        val changed = mutableListOf<SyncManifest>()
        val conflicts = mutableListOf<CloudConflict>()
        var downloaded = 0
        var uploaded = 0
        for (row in manifest) {
            val entry = own[row.id]
            val known = indexes[row.id]
            if (entry != null && known != null && row.revision == known.revision) {
                if (hash(entry) != known.hash) uploads += SyncUpload(entry, known.revision)
            } else changed += row
        }
        for (batch in batches(changed, 50, 10 * 1024 * 1024) { it.payloadBytes * 6 + 256 }) {
            progress("下载变化账目：已处理 $downloaded 笔")
            val rows = fetch(batch.map { it.id })
            require(rows.map { it.entry.id }.toSet() == batch.map { it.id }.toSet() && rows.size == batch.size) { "云端记录缺失或响应重复，请重新同步并核查服务端" }
            val expected = batch.associateBy { it.id }
            val downloads = mutableListOf<LedgerEntry>()
            val accepted = linkedMapOf<String, SyncIndex>()
            for (row in rows) {
                val id = row.entry.id
                require(row.revision >= expected.getValue(id).revision) { "云端版本回退，请停止同步" }
                val entry = own[id]
                val known = indexes[id]
                val remoteHash = hash(row.entry)
                val ownHash = entry?.let(hash)
                val remoteDeletedAt = row.entry.deletedAt
                when {
                    entry == null || (known != null && ownHash == known.hash) ||
                        (entry.deletedAt == null && remoteDeletedAt != null && entry.updatedAt <= remoteDeletedAt) -> {
                        downloads += row.entry
                        accepted[id] = SyncIndex(row.revision, remoteHash)
                    }
                    ownHash == remoteHash -> accepted[id] = SyncIndex(row.revision, remoteHash)
                    entry.deletedAt != null && remoteDeletedAt != null -> {
                        // Both devices agree that the row is deleted. Converge on the
                        // newer tombstone instead of asking the user to choose between
                        // two records that are no longer active.
                        if (remoteTombstoneWins(entry, row.entry, ownHash, remoteHash)) {
                            downloads += row.entry
                            accepted[id] = SyncIndex(row.revision, remoteHash)
                        } else uploads += SyncUpload(entry, row.revision)
                    }
                    else -> conflicts += CloudConflict(id, entry, row.entry, row.revision)
                }
            }
            // Never advance an index until the complete Room transaction has succeeded.
            if (downloads.isNotEmpty()) applyRemote(downloads)
            if (accepted.isNotEmpty()) remember(accepted)
            downloaded += downloads.size
        }
        local.filter { it.id !in remoteIds }.forEach { uploads += SyncUpload(it, 0) }
        for (batch in batches(uploads.sortedBy { it.entry.id }, 50, 6 * 1024 * 1024 - 1024, uploadBytes)) {
            progress("上传变化账目：已上传 $uploaded 笔")
            val results = push(batch)
            require(results.size == batch.size && results.map { it.id }.toSet() == batch.map { it.entry.id }.toSet()) { "批量上传响应缺失或重复，请重新同步" }
            val requested = batch.associateBy { it.entry.id }
            val accepted = linkedMapOf<String, SyncIndex>()
            for (result in results) {
                val request = requested.getValue(result.id)
                val remote = result.conflict
                if (remote == null) {
                    require(result.revision > request.expected) { "服务器版本无效" }
                    accepted[result.id] = SyncIndex(result.revision, hash(request.entry))
                } else {
                    require(remote.entry.id == result.id && remote.revision > 0) { "冲突响应无效" }
                    // A lost successful response is recoverable without creating a false conflict.
                    if (hash(remote.entry) == hash(request.entry)) {
                        accepted[result.id] = SyncIndex(remote.revision, hash(request.entry))
                    } else conflicts += CloudConflict(result.id, request.entry, remote.entry, remote.revision)
                }
            }
            if (accepted.isNotEmpty()) remember(accepted)
            uploaded += accepted.size
        }
        return SyncResult(uploaded, downloaded, conflicts)
    }

    private fun remoteTombstoneWins(local: LedgerEntry, remote: LedgerEntry, localHash: String, remoteHash: String): Boolean {
        val localDeleted = requireNotNull(local.deletedAt)
        val remoteDeleted = requireNotNull(remote.deletedAt)
        return when {
            remoteDeleted != localDeleted -> remoteDeleted > localDeleted
            remote.updatedAt != local.updatedAt -> remote.updatedAt > local.updatedAt
            else -> remoteHash > localHash
        }
    }

    companion object {
        internal fun <T> batches(rows: List<T>, maxRows: Int, maxBytes: Int, size: (T) -> Int): List<List<T>> {
            val result = mutableListOf<List<T>>()
            var batch = mutableListOf<T>()
            var bytes = 0
            for (row in rows) {
                val count = size(row)
                require(count in 1..maxBytes) { "单条账目及导入来源过大" }
                if (batch.size == maxRows || bytes + count > maxBytes) {
                    result += batch
                    batch = mutableListOf()
                    bytes = 0
                }
                batch += row
                bytes += count
            }
            if (batch.isNotEmpty()) result += batch
            return result
        }
    }
}
