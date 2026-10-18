package com.familyledger.app.ui

import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.familyledger.app.data.*
import com.familyledger.app.domain.*
import java.time.YearMonth
import org.junit.*

/** Screenshots contain synthetic fixtures only and exercise the production page composables. */
class ThemeScreenshotsTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: LedgerDatabase
    private lateinit var model: LedgerViewModel
    private val store = ViewModelStore()
    private var theme by mutableStateOf(AppTheme.WARM)
    private var page by mutableIntStateOf(0)
    private var largeType by mutableStateOf(false)
    private var narrow by mutableStateOf(false)
    private var selectedMonth by mutableStateOf(YearMonth.of(2026, 10))
    private val rows = listOf(
        entry("salary", EntryType.INCOME, 1450000, "工资", "2026-10-08", "老公"),
        entry("groceries", EntryType.EXPENSE, 12850, "食品酒水", "2026-10-17", "老婆"),
        entry("dinner", EntryType.EXPENSE, 8600, "食品酒水", "2026-10-17", "老公"),
        entry("travel", EntryType.EXPENSE, 35600, "交通出行", "2026-10-16", "老婆"),
        entry("gift", EntryType.EXPENSE, 20000, "人情往来", "2026-10-12", "家人").copy(isGift = true, counterparty = "亲友甲"),
        entry("old", EntryType.INCOME, 1200000, "工资", "2026-09-08", "老公")
    )
    private val state = LedgerState(loading = false, entries = rows, localRole = "老公", localAvatar = "man",
        cloudStatus = CloudStatus(true, "design@example.invalid", "synthetic-family", "我们的家", 0),
        chatMessages = listOf(ChatMessage("question", true, "这个月家里的开支怎么样？"),
            ChatMessage("long", false, (1..45).joinToString("\n") { "第${it}项生活观察：这是一段用于滚动边界检查的合成内容。" }),
            ChatMessage("answer", false, "## 这个月的生活账单\n已记录 **770.50 元**支出。\n- 买菜、晚餐与出行构成了日常开销。\n- 留意接下来的固定账单，预留生活预算。"),
            ChatMessage("request", true, "今晚和家人吃饭86元，我付的。")),
        quickDrafts = listOf(entry("proposal", EntryType.EXPENSE, 8600, "食品酒水", "2026-10-17", "老公").copy(note = "和家人一起晚餐")))

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, LedgerDatabase::class.java).build()
        compose.runOnIdle { model = LedgerViewModel(LedgerRepository(database)); store.put("screenshots", model) }
    }
    @After fun close() { compose.runOnIdle { store.clear() }; database.close() }

    @Test fun threeThemesFourPagesAndLargeText() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeType) 1.5f else 1f)) {
                FamilyLedgerTheme(theme, { theme = it }) {
                    Scaffold(bottomBar = { LedgerNavigationBar(page) { page = it } }) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
                            Box(if (narrow) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                            key(page) {
                                when (page) {
                                    0 -> QuickEntryScreen(model, state, SnackbarHostState(), null, {}, "design")
                                    1 -> LedgerScreen(state, selectedMonth, { selectedMonth = it }, {}, {}, {})
                                    2 -> ReportsScreen(rows, selectedMonth, { selectedMonth = it }, model, state)
                                    3 -> SettingsScreen(model, state)
                                    4 -> Column {
                                        LedgerPanel { RankedAmount("家庭成员名称完整显示", "¥ 99999999.99", Modifier.testTag("large_amount_row")) }
                                        ReportContent(rows.map { it.copy(amountMinor = 9999999999L, member = "家庭成员名称完整显示") }, selectedMonth, { selectedMonth = it },
                                            false, false, null, null, { _, _ -> }, {}, {})
                                    }
                                }
                            }
                            }
                        }
                    }
                }
            }
        }
        AppTheme.entries.forEach { choice ->
            compose.runOnIdle { theme = choice; largeType = false }
            listOf("chat", "ledger", "report", "settings").forEachIndexed { index, label ->
                compose.runOnIdle { page = index }
                compose.waitForIdle()
                if (index == 2) assertReportHeadingGap()
                screenshot("theme-${choice.storageId}-$label.png")
            }
        }
        compose.runOnIdle { theme = AppTheme.WARM; page = 1; largeType = true }
        compose.onNodeWithTag("ledger_list").assertIsDisplayed()
        screenshot("theme-large-type-ledger.png")
        compose.runOnIdle { page = 0 }
        compose.onNodeWithContentDescription("发送").assertIsDisplayed()
        compose.onNodeWithTag("chat_timeline").performScrollToIndex(0)
        compose.waitForIdle()
        val chatListBounds = compose.onNodeWithTag("chat_timeline").fetchSemanticsNode().boundsInRoot
        val chatHeaderBounds = compose.onNodeWithTag("chat_header").fetchSemanticsNode().boundsInRoot
        android.util.Log.i("AndroidRuntime", "theme-layout chat: list=$chatListBounds; header=$chatHeaderBounds")
        org.junit.Assert.assertTrue("Chat content must stay below the fixed header: list=$chatListBounds, header=$chatHeaderBounds", chatListBounds.top >= chatHeaderBounds.bottom)
        screenshot("theme-large-type-chat.png")
        AppTheme.entries.forEach { choice ->
            compose.runOnIdle { theme = choice; page = 0; narrow = true; largeType = true }
            compose.onNodeWithTag("chat_timeline").performScrollToIndex(0)
            compose.waitForIdle()
            val before = compose.onRoot().captureToImage().toPixelMap()
            val header = compose.onNodeWithTag("chat_header").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("chat_timeline").performTouchInput { swipeDown(durationMillis = 150) }
            compose.waitForIdle()
            val after = compose.onRoot().captureToImage().toPixelMap()
            for (y in 0 until header.bottom.toInt()) for (x in header.left.toInt() until header.right.toInt()) {
                org.junit.Assert.assertEquals("Scrolled chat painted over header or safe area at $x,$y in $choice", before[x,y], after[x,y])
            }
            screenshot("theme-${choice.storageId}-narrow-large-chat-scrolled.png")
        }
        AppTheme.entries.forEach { choice ->
            compose.runOnIdle { theme = choice; page = 4; narrow = true; largeType = true }
            compose.onAllNodesWithText("家庭成员名称完整显示").onFirst().assertIsDisplayed()
            compose.onAllNodesWithText("¥ 99999999.99").onFirst().assertIsDisplayed()
            compose.onNodeWithTag("report_section_members").performClick()
            compose.waitForIdle()
            assertReportHeadingGap()
            compose.onNodeWithTag("report_list").performScrollToNode(hasText("家庭成员名称完整显示"))
            val listBounds = compose.onNodeWithTag("report_list").fetchSemanticsNode().boundsInRoot
            val tabBounds = compose.onNodeWithTag("report_section_members").fetchSemanticsNode().boundsInRoot
            android.util.Log.i("AndroidRuntime", "theme-layout ${choice.storageId}: list=$listBounds; tab=$tabBounds; heading=${compose.onNodeWithText("成员支出").fetchSemanticsNode().boundsInRoot}")
            org.junit.Assert.assertTrue("Report content overlaps section tabs: list=$listBounds, tab=$tabBounds", listBounds.top >= tabBounds.bottom)
            screenshot("theme-${choice.storageId}-narrow-large-report.png")
        }
    }

    private fun assertReportHeadingGap() {
        val title = compose.onNodeWithTag("report_heading").fetchSemanticsNode().boundsInRoot
        val periods = compose.onNodeWithTag("report_period_choices").fetchSemanticsNode().boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        org.junit.Assert.assertTrue("Report title needs visible breathing room before period controls", periods.top - title.bottom >= 12f * density)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        if (name.contains("narrow-large-report")) {
            val capture = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "$name.compose.png")
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            capture.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "cp ${capture.absolutePath} /data/local/tmp/ledger-screens/$name.compose.png"
            ).let { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/ledger-screens/$name")
            .let { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
    }
    private fun entry(id: String, type: EntryType, cents: Long, category: String, date: String, member: String) = LedgerEntry(
        id, type, date, cents, category, account = "银行卡", member = member, recordedBy = "测试操作者")
}
