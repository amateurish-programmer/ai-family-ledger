package com.familyledger.app.data

import androidx.room.withTransaction
import com.familyledger.app.domain.*
import kotlinx.coroutines.flow.map

class LedgerRepository(private val database: LedgerDatabase) {
    private val dao = database.ledgerDao()
    val entries = dao.observeActive().map { rows -> rows.map { it.toEntry() } }
    suspend fun save(entry: LedgerEntry) = dao.save(EntryEntity.from(validateEntry(entry).copy(updatedAt = System.currentTimeMillis())))
    suspend fun delete(id: String) = dao.delete(id, System.currentTimeMillis())
    suspend fun backup(): String = BackupCodec.encode(dao.all().map { it.toEntry() })

    // Existing IDs, including tombstones, are deliberately never overwritten by restoration.
    suspend fun restore(entries: List<LedgerEntry>): Int = database.withTransaction {
        val valid = entries.map { EntryEntity.from(validateEntry(it)) }
        dao.insertNew(valid).count { it != -1L }
    }
}
