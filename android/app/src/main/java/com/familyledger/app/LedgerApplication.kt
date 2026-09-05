package com.familyledger.app

import android.app.Application
import androidx.room.Room
import com.familyledger.app.data.*

class LedgerApplication : Application() {
    private val database by lazy {
        Room.databaseBuilder(this, LedgerDatabase::class.java, "family-ledger.db").addMigrations(LedgerDatabase.MIGRATION_1_2).build()
    }
    val repository by lazy { LedgerRepository(database) }
    val cloud by lazy { CloudService(this) }
}
