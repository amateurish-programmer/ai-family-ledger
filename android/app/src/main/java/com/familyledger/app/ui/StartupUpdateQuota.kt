package com.familyledger.app.ui

import java.time.LocalDate

fun interface StartupUpdateQuota {
    /** Persist the date before allowing the request. Failed requests still consume this claim. */
    fun claim(): Boolean
}

class DailyStartupUpdateQuota(
    private val readDate: () -> String?,
    private val writeDate: (String) -> Boolean,
    private val today: () -> LocalDate = { LocalDate.now() },
) : StartupUpdateQuota {
    override fun claim(): Boolean = synchronized(lock) {
        val date = today().toString()
        if (readDate() == date) false else writeDate(date)
    }

    private companion object { val lock = Any() }
}
