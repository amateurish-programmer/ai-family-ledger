package com.familyledger.app.data

import androidx.room.withTransaction
import com.familyledger.app.domain.*
import kotlinx.coroutines.flow.map

class LedgerRepository(private val database: LedgerDatabase) {
    private val dao = database.ledgerDao()
    val entries = dao.observeActive().map { rows -> rows.map { it.toEntry() } }
    suspend fun save(entry: LedgerEntry) = dao.save(EntryEntity.from(validateEntry(entry).copy(updatedAt = System.currentTimeMillis())))
    suspend fun saveMany(rows: List<LedgerEntry>) = database.withTransaction {
        val now = System.currentTimeMillis()
        val valid = rows.map { EntryEntity.from(validateEntry(it).copy(updatedAt = now)) }
        dao.insertNew(valid)
    }
    suspend fun allEntries(): List<LedgerEntry> = dao.all().map { it.toEntry() }
    suspend fun applyRemote(rows: List<LedgerEntry>) = database.withTransaction {
        val valid = rows.map { EntryEntity.from(validateEntry(it)) }
        valid.forEach { dao.save(it) }
    }
    suspend fun delete(id: String) = dao.delete(id, System.currentTimeMillis())
    suspend fun backup(): String = BackupCodec.encode(dao.all().map { it.toEntry() })
    suspend fun exportSpreadsheet(): ByteArray = XlsxCodec.write(dao.all().filter { it.deletedAt == null }.map { it.toEntry() })
    suspend fun previewImport(bytes: ByteArray, fileName: String): ImportPreview = SpreadsheetImport.preview(bytes, fileName, dao.all().map { it.toEntry() })
    suspend fun commitImport(rows: List<LedgerEntry>, allowSimilar: Set<String> = emptySet()): Int = database.withTransaction {
        require(rows.all { it.origin != null }) { "导入记录缺少来源" }
        val valid = rows.map { EntryEntity.from(validateEntry(it)) }
        val existing = dao.all().map { it.toEntry() }
        val ids = existing.map { it.id }.toHashSet()
        val signatures = existing.map(SpreadsheetImport::fingerprint).toHashSet()
        rows.forEach { row ->
            val signature = SpreadsheetImport.fingerprint(row)
            require(row.id in ids || signature !in signatures || row.id in allowSimilar) { "账本已有相似记录，请取消后重新选择文件预览" }
            signatures += signature
        }
        dao.insertNew(valid).count { it != -1L }
    }
    suspend fun batches(): List<ImportBatch> = dao.all().map { it.toEntry() }.filter { it.origin != null }
        .groupBy { it.origin!!.fileHash }.map { (id, rows) ->
            val origin = rows.first().origin!!
            ImportBatch(id, origin.fileName, origin.importedAt, rows.size, rows.count { it.deletedAt == null })
        }.sortedByDescending { it.importedAt }
    suspend fun rollbackBatch(id: String): Int = database.withTransaction {
        val rows = dao.all().filter { it.deletedAt == null && it.toEntry().origin?.fileHash == id }
        val now = System.currentTimeMillis()
        rows.forEach { dao.delete(it.id, now) }
        rows.size
    }

    // Existing IDs, including tombstones, are deliberately never overwritten by restoration.
    suspend fun restore(entries: List<LedgerEntry>): Int = database.withTransaction {
        val valid = entries.map { EntryEntity.from(validateEntry(it)) }
        dao.insertNew(valid).count { it != -1L }
    }
}
