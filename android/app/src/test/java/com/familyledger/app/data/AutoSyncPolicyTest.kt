package com.familyledger.app.data

import org.junit.Assert.*
import org.junit.Test

class AutoSyncPolicyTest {
    @Test fun successfulSyncSuppressesForegroundRequestsForOneMinute() {
        assertFalse(AutoSyncPolicy.isDue(1_000_000, 1_000_000))
        assertFalse(AutoSyncPolicy.isDue(1_000_000, 1_059_999))
        assertTrue(AutoSyncPolicy.isDue(1_000_000, 1_060_000))
        assertTrue(AutoSyncPolicy.isDue(1_000_000, 1_060_001))
    }
    @Test fun firstRunAndClockRollbackDoNotBlockSyncForever() {
        assertTrue(AutoSyncPolicy.isDue(0, 1_000_000))
        assertTrue(AutoSyncPolicy.isDue(1_000_000, 900_000))
    }
    @Test fun persistedLastSuccessStillThrottlesAfterProcessRestart() {
        val savedSuccess = 1_000_000L
        assertFalse(AutoSyncPolicy.isDue(savedSuccess, savedSuccess + 40_000))
        assertTrue(AutoSyncPolicy.isDue(savedSuccess, savedSuccess + 65_000))
    }
}
