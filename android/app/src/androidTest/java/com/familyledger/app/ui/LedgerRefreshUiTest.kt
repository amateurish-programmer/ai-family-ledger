package com.familyledger.app.ui

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.YearMonth

class LedgerRefreshUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pullOnEmptyLedgerRefreshesAndBusyIndicatorAppearsOnlyDuringSync() {
        var state by mutableStateOf(LedgerState(loading = false))
        var calls = 0
        compose.setContent { FamilyLedgerTheme {
            LedgerScreen(state, YearMonth.of(2026, 9), {}, {}, {}, onRefresh = {
                calls++; state = state.copy(busy = true, syncing = true)
            })
        } }
        compose.onNodeWithTag("sync_status").assertDoesNotExist()
        fun pull() = compose.onNodeWithTag("ledger_list").performTouchInput {
            swipe(Offset(centerX, height * .25f), Offset(centerX, height * .9f), 700)
        }
        pull()
        compose.waitUntil(5000) { calls == 1 }
        compose.onNodeWithTag("sync_status").assertExists()
        compose.onNodeWithText("正在同步…").assertExists()
        compose.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /data/local/tmp/ledger-screens/ledger-refresh-syncing.png")
        ParcelFileDescriptor.AutoCloseInputStream(screenshot).use { it.readBytes() }
        pull(); assertEquals(1, calls)
        compose.runOnIdle { state = state.copy(busy = false, syncing = false) }
        compose.onNodeWithTag("sync_status").assertDoesNotExist()
        pull(); compose.waitUntil(5000) { calls == 2 }
    }

    @Test fun loadingOrOtherWorkDoesNotTriggerRefreshOrShowSyncIndicator() {
        var state by mutableStateOf(LedgerState(loading = true))
        var calls = 0
        compose.setContent { FamilyLedgerTheme {
            LedgerScreen(state, YearMonth.of(2026, 9), {}, {}, {}, onRefresh = { calls++ })
        } }
        fun pull() = compose.onNodeWithTag("ledger_list").performTouchInput {
            swipe(Offset(centerX, height * .25f), Offset(centerX, height * .9f), 700)
        }
        pull(); assertEquals(0, calls)
        compose.runOnIdle { state = state.copy(loading = false, busy = true) }
        pull(); assertEquals(0, calls)
        compose.onNodeWithTag("sync_status").assertDoesNotExist()
    }
}
