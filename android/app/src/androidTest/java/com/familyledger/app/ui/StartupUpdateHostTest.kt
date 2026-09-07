package com.familyledger.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.familyledger.app.data.AppUpdate
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class StartupUpdateHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val checks = AtomicInteger()
    private val release = AppUpdate(1, 14, "1.2.0", 26, AppUpdate.PACKAGE_NAME,
        "releases/14/ai-family-ledger-v1.2.0.apk", 100, "0".repeat(64),
        "合成启动更新说明", Instant.parse("2026-09-07T00:00:00Z"))

    private fun seedActivityModel(): AppUpdateViewModel {
        val model = AppUpdateViewModel("1.1.0", { checks.incrementAndGet(); release },
            { _, _ -> error("Startup must not download") },
            { _, _ -> error("Startup must not install") }, StartupUpdateQuota { true })
        compose.activityRule.scenario.onActivity { activity ->
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = model as T
            }
            assertEquals(model, ViewModelProvider(activity, factory)[AppUpdateViewModel::class.java])
        }
        return model
    }

    @Test fun pendingPromptWaitsForLedgerThenNavigatesAndClearsWithoutDownloading() {
        val model = seedActivityModel()
        var ledgerBusy by mutableStateOf(true)
        var opened = 0
        compose.setContent { FamilyLedgerTheme {
            StartupUpdateHost(ledgerBusy) { opened++ }
        } }
        compose.waitUntil(timeoutMillis = 5000) { model.startupPrompt.value != null }
        compose.onNodeWithText("查看更新").assertDoesNotExist()
        compose.runOnIdle { ledgerBusy = false }
        compose.onNodeWithText("发现新版本 v1.2.0").assertIsDisplayed()
        compose.onNodeWithText(release.notes).assertIsDisplayed()
        compose.onNodeWithText("查看更新").performClick()
        compose.onNodeWithText("查看更新").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, opened)
            assertEquals(1, checks.get())
            assertNull(model.startupPrompt.value)
            assertEquals(UpdatePhase.AVAILABLE, model.state.value.phase)
            assertEquals(release, model.state.value.update)
            assertNull(model.state.value.file)
            assertNull(model.state.value.installRequest)
        }
    }

    @Test fun laterDismissalDoesNotReplayWhenHostLeavesAndReentersComposition() {
        val model = seedActivityModel()
        var showHost by mutableStateOf(true)
        compose.setContent { FamilyLedgerTheme {
            if (showHost) StartupUpdateHost(false) { error("Later must not navigate") }
        } }
        compose.waitUntil(timeoutMillis = 5000) { model.startupPrompt.value != null }
        compose.onNodeWithText("稍后").performClick()
        compose.runOnIdle { showHost = false }
        compose.runOnIdle { showHost = true }
        compose.onNodeWithText("稍后").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, checks.get())
            assertNull(model.startupPrompt.value)
            assertEquals(release, model.state.value.update)
        }
    }
}
