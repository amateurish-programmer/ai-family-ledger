package com.familyledger.app.ui

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class StartupUpdateQuotaTest {
    @Test fun foregroundQuotaAllowsAFreshCheckEveryTimeTheAppStarts() {
        val quota = ForegroundStartupUpdateQuota()
        assertTrue(quota.claim())
        assertTrue(quota.claim())
        assertTrue(quota.claim())
    }

    @Test fun sameDayAndRecreatedGateCannotRetryButNextLocalDayCan() {
        var saved: String? = null
        var today = LocalDate.of(2026, 9, 7)
        fun gate() = DailyStartupUpdateQuota({ saved }, { saved = it; true }, { today })
        assertTrue(gate().claim())
        assertFalse(gate().claim()) // persisted date survives gate/process recreation
        today = LocalDate.of(2026, 9, 8)
        assertTrue(gate().claim())
        assertEquals("2026-09-08", saved)
    }

    @Test fun failedPersistenceDoesNotAuthorizeNetworkAttempt() {
        assertFalse(DailyStartupUpdateQuota({ null }, { false }).claim())
    }
}
