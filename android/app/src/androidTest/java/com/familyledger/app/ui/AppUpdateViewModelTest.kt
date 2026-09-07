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
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val release = AppUpdate(1, 12, "0.12.0", 26, AppUpdate.PACKAGE_NAME,
        "releases/12/AI家庭账本-v0.12.0.apk", 100, "0".repeat(64), "合成更新说明", Instant.parse("2026-09-07T00:00:00Z"))
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
