package com.familyledger.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import com.familyledger.app.data.AppUpdate
import java.time.Instant
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppUpdateUiTest {
    @get:Rule val compose = createComposeRule()
    private val release = AppUpdate(1, 12, "0.12.0", 26, AppUpdate.PACKAGE_NAME,
        "releases/12/AI家庭账本-v0.12.0.apk", 18000000, "0".repeat(64), "优化家庭账本使用体验\n修复已知问题", Instant.parse("2026-09-07T00:00:00Z"))

    @Test fun updateCardShowsNotesProgressCancelAndBusyInstallGuard() {
        var state by mutableStateOf(AppUpdateState())
        var ledgerBusy by mutableStateOf(false)
        var cancelled = 0; var installs = 0
        compose.setContent { FamilyLedgerTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                AppUpdateCard("0.11.0", state, ledgerBusy,
                    { state = AppUpdateState(UpdatePhase.AVAILABLE, release) },
                    { state = state.copy(phase = UpdatePhase.DOWNLOADING, received = 9000000, total = 18000000) },
                    { cancelled++; state = state.copy(phase = UpdatePhase.AVAILABLE) }, { installs++ })
            }
        } }
        compose.onNodeWithText("检查更新").performScrollTo().performClick()
        compose.onNodeWithText(release.notes).assertExists()
        screenshot("ledger-update-available.png")
        compose.onNodeWithText("下载更新").performScrollTo().performClick()
        compose.onNodeWithText("已下载 8.6 MB / 17.2 MB").assertExists()
        screenshot("ledger-update-progress.png")
        compose.onNodeWithText("取消下载").performScrollTo().performClick()
        assertEquals(1, cancelled)
        compose.runOnIdle { state = state.copy(phase = UpdatePhase.READY, file = File("synthetic.apk")); ledgerBusy = true }
        compose.onNodeWithText("安装更新").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { ledgerBusy = false }
        compose.onNodeWithText("安装更新").assertIsEnabled().performClick()
        assertEquals(1, installs)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/ledger-screens/$name")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
}
