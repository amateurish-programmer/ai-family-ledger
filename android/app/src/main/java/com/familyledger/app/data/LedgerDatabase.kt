package com.familyledger.app.data

import androidx.room.*
import com.familyledger.app.domain.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "entries", indices = [Index("occurredOn")])
data class EntryEntity(
    @PrimaryKey val id: String,
    val type: String, val occurredOn: String, val amountMinor: Long,
    val categoryL1: String, val categoryL2: String, val account: String,
    val member: String, val recordedBy: String, val merchant: String,
    val project: String, val note: String, val currency: String,
    val updatedAt: Long, val deletedAt: Long?
) {
    fun toEntry() = LedgerEntry(id, EntryType.valueOf(type), occurredOn, amountMinor,
        categoryL1, categoryL2, account, member, recordedBy, merchant, project, note, currency, updatedAt, deletedAt)

    companion object {
        fun from(e: LedgerEntry) = EntryEntity(e.id, e.type.name, e.occurredOn, e.amountMinor,
            e.categoryL1, e.categoryL2, e.account, e.member, e.recordedBy, e.merchant,
            e.project, e.note, e.currency, e.updatedAt, e.deletedAt)
    }
}

@Dao
interface LedgerDao {
    @Query("SELECT * FROM entries WHERE deletedAt IS NULL ORDER BY occurredOn DESC, updatedAt DESC, id ASC")
    fun observeActive(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries ORDER BY occurredOn DESC, id ASC")
    suspend fun all(): List<EntryEntity>

    @Upsert suspend fun save(entry: EntryEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertNew(entries: List<EntryEntity>): List<Long>

    @Query("UPDATE entries SET deletedAt = :now, updatedAt = :now WHERE id = :id AND deletedAt IS NULL")
    suspend fun delete(id: String, now: Long)
}

@Database(entities = [EntryEntity::class], version = 1, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase() { abstract fun ledgerDao(): LedgerDao }
