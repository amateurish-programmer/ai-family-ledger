package com.familyledger.app

import android.app.Application
import androidx.room.Room
import com.familyledger.app.data.*

class LedgerApplication : Application() {
    private val database by lazy {
        Room.databaseBuilder(this, LedgerDatabase::class.java, "family-ledger.db").build()
    }
    val repository by lazy { LedgerRepository(database) }
}
