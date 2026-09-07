package com.familyledger.app.ui

import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.familyledger.app.data.AppUpdate
import java.io.File
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class AppUpdateViewModelTest {
    @Test fun noNewVersionLeavesExistingNoticeUntouchedAndUsesPersistentQuota() = runBlocking {
        val context = instrumentation.targetContext.applicationContext
        val preferences = context.getSharedPreferences("startup_quota_test", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        fun quota() = DailyStartupUpdateQuota(
            { preferences.getString("date", null) },
            { preferences.edit().putString("date", it).commit() })
        val completed = CompletableDeferred<Unit>()
        var calls = 0
        val first = AppUpdateViewModel("1.0.0", { calls++; completed.complete(Unit); null },
            { _, _ -> error("No download") }, { _, _ -> }, quota())
        val restarted = AppUpdateViewModel("1.0.0", { calls++; null },
            { _, _ -> error("No download") }, { _, _ -> }, quota())
        try {
            main { first.notice("已有提示"); first.checkOnStartup() }
            withTimeout(5000) { completed.await() }
            main { }
            assertEquals(AppUpdateState(message = "已有提示"), first.state.value)
            assertNull(first.startupPrompt.value)
            // Reading with a new quota/model represents process recreation using the saved value.
            assertFalse(quota().claim())
            main { restarted.checkOnStartup(); restarted.check() }
            await(restarted, UpdatePhase.IDLE)
            assertEquals(2, calls) // first automatic + restarted manual only
        } finally { close(first); close(restarted); preferences.edit().clear().commit() }
    }

    @Test fun existingReadyFileAndInstallRequestPreventAutomaticStateReplacement() = runBlocking {
        var claims = 0
        var calls = 0
        val model = AppUpdateViewModel("1.0.0", { calls++; release }, { _, _ -> file }, { _, _ -> },
            StartupUpdateQuota { claims++; true })
        try {
            main { model.check(); model.download() }; await(model, UpdatePhase.READY)
            main { model.checkOnStartup(); model.prepareInstall() }
            withTimeout(5000) { model.state.first { it.installRequest != null } }
            val before = model.state.value
            main { model.checkOnStartup() }
            assertEquals(before, model.state.value)
            assertEquals(0, claims)
            assertEquals(1, calls)
        } finally { close(model) }
    }

    @Test fun silentFailureUsesDailyQuotaButManualChecksRemainUnlimited() = runBlocking {
        var saved: String? = null
        var calls = 0
        val model = AppUpdateViewModel("1.0.0", { calls++; throw java.io.IOException() },
            { _, _ -> error("No automatic download") }, { _, _ -> },
            DailyStartupUpdateQuota({ saved }, { saved = it; true }))
        try {
            main { model.checkOnStartup() }
            withTimeout(5000) { while (saved == null) delay(10) }
            main { }
            // Drain the IO quota claim and the main-thread check completion.
            withTimeout(5000) { while (calls == 0) delay(10) }
            main { model.checkOnStartup() }
            assertEquals(AppUpdateState(), model.state.value)
            assertNull(model.startupPrompt.value)
            main { model.check() }
            await(model, UpdatePhase.IDLE)
            main { model.check() }; await(model, UpdatePhase.IDLE)
            assertEquals(3, calls)
        } finally { close(model) }
    }

    @Test fun silentNewVersionCanBeDismissedWithoutLosingDownloadState() = runBlocking {
        var claimed = false
        val model = AppUpdateViewModel("1.0.0", { release }, { _, _ -> file }, { _, _ -> },
            StartupUpdateQuota { if (claimed) false else { claimed = true; true } })
        try {
            main { model.checkOnStartup(); model.checkOnStartup() }
            withTimeout(5000) { model.startupPrompt.first { it != null } }
            assertEquals(release, model.state.value.update)
            main { model.dismissStartupPrompt(); model.download() }
            await(model, UpdatePhase.READY)
            main { model.checkOnStartup() }
            assertNull(model.startupPrompt.value)
            assertEquals(file, model.state.value.file)
            assertEquals(UpdatePhase.READY, model.state.value.phase)
        } finally { close(model) }
    }

    @Test fun manualCheckWaitsForSilentFlightAndOwnsResult() = runBlocking {
        val pending = CompletableDeferred<AppUpdate?>()
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val model = AppUpdateViewModel("1.0.0", {
            if (++calls == 1) { entered.complete(Unit); pending.await() } else null
        }, { _, _ -> error("No automatic download") }, { _, _ -> }, StartupUpdateQuota { true })
        try {
            main { model.checkOnStartup() }
            withTimeout(5000) { entered.await() }
            main { model.checkOnStartup(); model.check() }
            assertEquals(1, calls)
            assertEquals(UpdatePhase.CHECKING, model.state.value.phase)
            pending.complete(release)
            await(model, UpdatePhase.IDLE)
            assertEquals(2, calls)
            assertNull(model.startupPrompt.value)
            assertNull(model.state.value.update)
            assertEquals("已是最新版本", model.state.value.message)
        } finally { pending.complete(null); close(model) }
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val release = AppUpdate(1, 12, "0.12.0", 26, AppUpdate.PACKAGE_NAME,
        "releases/12/ai-family-ledger-v0.12.0.apk", 100, "0".repeat(64), "合成更新说明", Instant.parse("2026-09-07T00:00:00Z"))
    private val file = File("synthetic-only.apk")
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private suspend fun await(model: AppUpdateViewModel, phase: UpdatePhase) = withTimeout(5000) { model.state.first { it.phase == phase } }
    private fun close(model: AppUpdateViewModel) = main { model.viewModelScope.cancel() }

    @Test fun cancellingWaitsForCleanupBeforeRetryAndIgnoresLateProgress() = runBlocking {
        val cleanupStarted = CompletableDeferred<Unit>(); val cleanupDone = CompletableDeferred<Unit>()
        var calls = 0
        val model = AppUpdateViewModel("0.11.0", { release }, { _, progress ->
            calls++
            progress(30, 100)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    cleanupDone.await()
                    progress(99, 100)
                }
            }
        }, { _, _ -> })
        try {
            main { model.check() }; await(model, UpdatePhase.AVAILABLE)
            main { model.download() }
            withTimeout(5000) { model.state.first { it.received == 30L } }
            main { model.cancelDownload() }
            withTimeout(5000) { cleanupStarted.await() }
            main { model.download(); model.check() }
            assertEquals(1, calls)
            assertEquals(UpdatePhase.CANCELLING, model.state.value.phase)
            cleanupDone.complete(Unit)
            await(model, UpdatePhase.AVAILABLE)
            assertEquals(30L, model.state.value.received)
            assertNull(model.state.value.file)
            main { model.download() }
            assertEquals(2, calls)
        } finally { cleanupDone.complete(Unit); close(model) }
    }

    @Test fun installRequestWaitsForCurrentScreenAndIsConsumedExactlyOnce() = runBlocking {
        var verified = 0
        val model = AppUpdateViewModel("0.11.0", { release }, { _, progress -> progress(100, 100); file }, { _, _ -> verified++ })
        try {
            main { model.check(); model.download() }
            await(model, UpdatePhase.READY)
            main { model.prepareInstall(); model.prepareInstall() }
            withTimeout(5000) { model.state.first { it.installRequest != null } }
            assertEquals(1, verified)
            assertEquals(file, model.state.value.installRequest)
            main {
                assertEquals(file, model.consumeInstallRequest())
                assertNull(model.consumeInstallRequest())
                model.prepareInstall()
            }
            withTimeout(5000) { model.state.first { it.installRequest != null } }
            assertEquals(2, verified) // every retry must revalidate, even a previously verified file
        } finally { close(model) }
    }

    @Test fun failedRevalidationNeverEmitsInstallerRequest() = runBlocking {
        val model = AppUpdateViewModel("0.11.0", { release }, { _, _ -> file }, { _, _ -> throw IllegalArgumentException("安装包校验失败") })
        try {
            main { model.check(); model.download() }; await(model, UpdatePhase.READY)
            main { model.prepareInstall() }; await(model, UpdatePhase.AVAILABLE)
            assertNull(model.state.value.installRequest)
            assertNull(model.state.value.file)
            assertTrue(model.state.value.error)
        } finally { close(model) }
    }

    @Test fun checkAndDownloadFailuresCanRetryWithoutClaimingLatest() = runBlocking {
        var checks = 0; var downloads = 0
        val model = AppUpdateViewModel("0.11.0", { if (++checks == 1) throw java.io.IOException() else release },
            { _, _ -> if (++downloads == 1) throw java.io.IOException() else file }, { _, _ -> })
        try {
            main { model.check() }; await(model, UpdatePhase.IDLE)
            assertTrue(model.state.value.error)
            assertNotEquals("已是最新版本", model.state.value.message)
            main { model.check() }; await(model, UpdatePhase.AVAILABLE)
            main { model.download() }; await(model, UpdatePhase.AVAILABLE)
            assertTrue(model.state.value.error)
            main { model.download() }; await(model, UpdatePhase.READY)
            assertFalse(model.state.value.error)
        } finally { close(model) }
    }

    @Test fun sameOrNewerVersionHasNoDownloadAction() = runBlocking {
        val model = AppUpdateViewModel("0.11.0", { null }, { _, _ -> error("Download must not start") }, { _, _ -> })
        try {
            main { model.check(); model.download() }; await(model, UpdatePhase.IDLE)
            assertEquals("已是最新版本", model.state.value.message)
            assertNull(model.state.value.update)
        } finally { close(model) }
    }
}
