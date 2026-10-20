package com.familyledger.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.familyledger.app.data.*
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** All reports are synthetic; injected probes never make a network request. */
class NetworkDiagnosticsUiTest {
    @get:Rule val compose = createComposeRule()
    private val initial = NetworkDiagnosticReport("2026-10-20T00:00:00Z", "已收集本机网络信息", "{\"synthetic\":\"初始报告\"}")
    private val completed = NetworkDiagnosticReport("2026-10-20T00:01:00Z", "诊断完成", "{\"synthetic\":\"完成报告\"}")

    /** Catches hiding the troubleshooting route behind account or family membership. */
    @Test fun signedOutSettingsCanOpenDiagnosticsAndReturnHome() {
        val database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            LedgerDatabase::class.java).build()
        val store = ViewModelStore()
        lateinit var model: LedgerViewModel
        try {
            compose.runOnIdle { model = LedgerViewModel(LedgerRepository(database)); store.put("diagnostics-test", model) }
            compose.setContent { FamilyLedgerTheme { SettingsScreen(model, LedgerState(loading = false)) } }
            compose.onNodeWithText("网络诊断").performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("开始诊断").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("开始诊断").performScrollTo().assertIsEnabled()
            compose.onNodeWithContentDescription("返回设置").performClick()
            compose.onNodeWithText("角色设置").performScrollTo().assertIsDisplayed()
        } finally { compose.runOnIdle { store.clear() }; database.close() }
    }

    /** Catches duplicate probes, missing cancellation, and exporting a different report than shown. */
    @Test fun runIsExclusiveCancellationIsQuietAndExportsUseTheDisplayedSnapshot() {
        val gate = CompletableDeferred<NetworkDiagnosticReport>()
        var runs = 0
        var saved: NetworkDiagnosticReport? = null
        var shared: NetworkDiagnosticReport? = null
        compose.setContent { FamilyLedgerTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                NetworkDiagnosticsPanel(initial, runDiagnostics = { progress ->
                    runs++
                    progress("正在检查公开服务…")
                    gate.await()
                }, onSave = { saved = it }, onShare = { shared = it })
            }
        } }
        compose.onNodeWithText("开始诊断").performScrollTo().performClick()
        compose.onNodeWithText("开始诊断").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText("正在检查公开服务…").assertExists()
        compose.runOnIdle { assertEquals(1, runs) }
        compose.onNodeWithText("保存诊断文件").assertIsNotEnabled()
        compose.onNodeWithText("取消诊断").performScrollTo().performClick()
        compose.onNodeWithText("诊断已取消").assertExists()
        compose.onNodeWithText("开始诊断").assertIsEnabled()
        compose.onNodeWithText("诊断未完成，请稍后重试").assertDoesNotExist()
        compose.onNodeWithText("保存诊断文件").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(initial, saved) }
        compose.onNodeWithText("开始诊断").performScrollTo().performClick()
        compose.runOnIdle { gate.complete(completed) }
        compose.waitUntil(timeoutMillis = 5_000) { runs == 2 }
        compose.onNodeWithText(completed.summary).assertExists()
        compose.onNodeWithText("分享诊断文件").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(completed, shared) }
        compose.onNodeWithText(completed.text).assertDoesNotExist()
        compose.onNodeWithText("查看详细报告").performScrollTo().performClick()
        compose.onNodeWithText(completed.text).assertExists()
    }

    /** Catches leaking the exception text or discarding the safe snapshot after a failed probe. */
    @Test fun failedProbeShowsSafeMessageAndKeepsThePreviousReport() {
        var saved: NetworkDiagnosticReport? = null
        compose.setContent { FamilyLedgerTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                NetworkDiagnosticsPanel(initial, { throw IllegalStateException("secret-token-synthetic") },
                    onSave = { saved = it }, onShare = {})
            }
        } }
        compose.onNodeWithText("开始诊断").performScrollTo().performClick()
        compose.onNodeWithText("诊断未完成，请稍后重试").assertExists()
        compose.onNodeWithText("secret-token-synthetic").assertDoesNotExist()
        compose.onNodeWithText("保存诊断文件").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(initial, saved) }
    }

    /** Catches doing persisted-snapshot I/O on the main thread or probing on page entry. */
    @Test fun openingDiagnosticsLoadsOfflineSnapshotInBackgroundWithoutRunningProbes() {
        var probeRuns = 0
        compose.setContent { FamilyLedgerTheme {
            NetworkDiagnosticsContent(NetworkDiagnosticExports(false, null, {}, {}), loadSnapshot = {
                check(Looper.myLooper() != Looper.getMainLooper()) { "Snapshot read on main thread" }
                initial
            }, runDiagnostics = { probeRuns++; completed })
        } }
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText(initial.summary).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("开始诊断").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, probeRuns) }
    }

    /** Catches raw storage errors reaching the page and a failed initial read having no recovery. */
    @Test fun failedOfflineSnapshotShowsSafeRetryAndCanRecover() {
        val attempts = AtomicInteger()
        compose.setContent { FamilyLedgerTheme {
            NetworkDiagnosticsContent(NetworkDiagnosticExports(false, null, {}, {}), loadSnapshot = {
                if (attempts.getAndIncrement() == 0) throw IllegalStateException("synthetic-private-storage-path")
                initial
            }, runDiagnostics = { completed })
        } }
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("暂时无法读取诊断信息，请重试").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("synthetic-private-storage-path").assertDoesNotExist()
        compose.onNodeWithText("重新读取").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText(initial.summary).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("开始诊断").assertIsEnabled()
    }

    /** Catches losing completed probes when navigating away or restoring an activity. */
    @Test fun completedReportSurvivesRouteExitAndSavedStateRestore() {
        val restoration = StateRestorationTester(compose)
        var runs = 0
        var snapshots = 0
        restoration.setContent { FamilyLedgerTheme {
            var saved by rememberSaveable(stateSaver = NetworkDiagnosticReportSaver) { mutableStateOf<NetworkDiagnosticReport?>(null) }
            var visible by rememberSaveable { mutableStateOf(true) }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                androidx.compose.material3.TextButton(onClick = { visible = !visible }) {
                    androidx.compose.material3.Text(if (visible) "返回测试设置" else "打开测试诊断")
                }
                if (visible) NetworkDiagnosticsContent(NetworkDiagnosticExports(false, null, {}, {}),
                    loadSnapshot = { snapshots++; initial }, runDiagnostics = { runs++; completed },
                    completedReport = saved, onReportCompleted = { saved = it })
            }
        } }
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("开始诊断").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("开始诊断").performScrollTo().performClick()
        compose.onNodeWithText(completed.summary).assertExists()
        compose.onNodeWithText("返回测试设置").performScrollTo().performClick()
        compose.onNodeWithText("打开测试诊断").performClick()
        compose.onNodeWithText(completed.summary).assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(completed.summary).assertExists()
        compose.onNodeWithText("报告时间：${completed.generatedAt}").assertExists()
        compose.onNodeWithText("查看详细报告").performScrollTo().performClick()
        compose.onNodeWithText(completed.text).assertExists()
        compose.runOnIdle { assertEquals(1, runs); assertEquals(1, snapshots) }
    }

    /** Catches losing picker results during navigation, changed content, or treating cancel as an error. */
    @Test fun savePickerKeepsTheClickedSnapshotAndCancellationDoesNotWrite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val target = File.createTempFile("synthetic-diagnostic-picker-", ".txt", context.cacheDir)
        var requestCode = 0
        var launched: Intent? = null
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) {
                requestCode = code
                launched = contract.createIntent(context, input)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        var report by mutableStateOf(initial)
        var visible by mutableStateOf(true)
        lateinit var exports: NetworkDiagnosticExports
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { FamilyLedgerTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    exports = rememberNetworkDiagnosticExports()
                    if (visible) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        NetworkDiagnosticsPanel(report, { completed }, { exports.onSave(report) }, {}, exports.busy, exports.message)
                    }
                }
            } }
            compose.onNodeWithText("保存诊断文件").performScrollTo().performClick()
            restoration.emulateSavedInstanceStateRestore()
            compose.runOnIdle {
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, launched!!.action)
                assertEquals("text/plain", launched!!.type)
                assertTrue(launched!!.getStringExtra(Intent.EXTRA_TITLE)!!.endsWith(".txt"))
                report = completed
                visible = false
                registry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(target)))
            }
            compose.waitUntil(timeoutMillis = 5_000) { target.length() > 0 }
            assertArrayEquals(initial.text.toByteArray(Charsets.UTF_8), target.readBytes())
            compose.runOnIdle { visible = true }
            compose.onNodeWithText("诊断文件已保存，可发给维护者").assertExists()
            compose.onNodeWithText("保存诊断文件").performScrollTo().performClick()
            compose.runOnIdle { registry.dispatchResult(requestCode, Activity.RESULT_CANCELED, null) }
            compose.onNodeWithText("无法保存诊断文件，请重试").assertDoesNotExist()
            compose.onNodeWithText("保存诊断文件").assertIsEnabled()
            assertArrayEquals(initial.text.toByteArray(Charsets.UTF_8), target.readBytes())
        } finally { target.delete() }
    }

    /** Catches actions clipped or placed beyond scrolling at 320 dp with large text. */
    @Test fun narrowLargeTextKeepsActionsReachableAcrossAllThemes() {
        var theme by mutableStateOf(AppTheme.WARM)
        var saves = 0
        var shares = 0
        var exportBusy by mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                FamilyLedgerTheme(theme) {
                    Column(Modifier.width(320.dp).fillMaxHeight().windowInsetsPadding(WindowInsets.safeDrawing)
                        .testTag("diagnostic_safe_content").verticalScroll(rememberScrollState()).padding(18.dp)) {
                        NetworkDiagnosticsPanel(initial, { CompletableDeferred<NetworkDiagnosticReport>().await() },
                            { saves++ }, { shares++ }, exportBusy)
                    }
                }
            }
        }
        AppTheme.entries.forEach { choice ->
            compose.runOnIdle { theme = choice; exportBusy = true }
            compose.onNodeWithText("开始诊断").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("保存诊断文件").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithText("分享诊断文件").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
            compose.runOnIdle { exportBusy = false }
            compose.onNodeWithText("开始诊断").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText("取消诊断").performScrollTo().assertIsDisplayed()
            screenshot("ledger-network-diagnostics-${choice.name.lowercase()}-320-large-running.png")
            compose.onNodeWithText("取消诊断").performClick()
            compose.onNodeWithText("保存诊断文件").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText("分享诊断文件").performScrollTo().assertIsDisplayed().performClick()
            val shareBounds = compose.onNodeWithText("分享诊断文件").fetchSemanticsNode().boundsInRoot
            val safeBounds = compose.onNodeWithTag("diagnostic_safe_content").fetchSemanticsNode().boundsInRoot
            assertTrue("Diagnostic actions must stay within the system safe area", shareBounds.bottom <= safeBounds.bottom)
            screenshot("ledger-network-diagnostics-${choice.name.lowercase()}-320-large.png")
        }
        compose.runOnIdle { assertEquals(3, saves); assertEquals(3, shares) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "screencap -p /data/local/tmp/ledger-screens/$name")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
}
