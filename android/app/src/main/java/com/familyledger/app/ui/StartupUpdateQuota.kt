package com.familyledger.app.ui

import java.time.LocalDate

fun interface StartupUpdateQuota {
    /** Claim one automatic foreground check. */
    fun claim(): Boolean
}

/** Automatic checks are allowed again whenever the app enters the foreground. */
class ForegroundStartupUpdateQuota : StartupUpdateQuota {
    override fun claim(): Boolean = true
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
