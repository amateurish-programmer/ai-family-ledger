package com.familyledger.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import com.familyledger.app.data.*
import com.familyledger.app.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.YearMonth

class ChatAnalysisUiTest {
    @get:Rule val compose = createComposeRule()
    private fun row(id: String, cents: Long, child: String, member: String = "老公") =
        LedgerEntry(id, EntryType.EXPENSE, "2026-11-01", cents, "行车交通", categoryL2 = child,
            account = "现金", member = member, recordedBy = "老婆")

    @Test fun detailsShowExactCarTotalAndSeparateAmbiguousRecords() {
        val query = ChatQuery("2026-01-01", "2026-11-12", "老公", "", "", label = "今年养车", type = "EXPENSE", topic = "private_car")
        val rows = listOf(row("oil", 20000, "加油"), row("park", 3000, "停车"), row("bus", 600, "地铁"),
            row("other", 5000, "加油", "老婆"), row("unknown", 8000, "交通费"))
        compose.setContent { FamilyLedgerTheme { ChatQueryDetailsSheet(listOf(query), rows, {}) } }
        compose.onNodeWithText("支出 ¥ 230.00 · 2 笔").assertIsDisplayed()
        compose.onNodeWithText("2026-11-01 · 支出 ¥ 6.00").assertDoesNotExist()
        compose.onNodeWithText("待核对", substring = false).performClick()
        compose.onNodeWithText("待核对 1 笔").assertIsDisplayed()
        compose.onNodeWithText("2026-11-01 · 支出 ¥ 80.00").assertExists()
    }

    @Test fun reportMemberIncomeOpensChatWithCurrentPeriodAndDirection() {
        var selected: ChatQuery? = null
        val entry = row("salary", 12345, "工资", "老婆").copy(type = EntryType.INCOME, categoryL1 = "职业收入")
        compose.setContent {
            FamilyLedgerTheme {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    ReportContent(listOf(entry), YearMonth.of(2026, 11), {}, false, false, null, null,
                        { _, _ -> fail("Expected selected member analysis") }, {}, {},
                        onAnalyzeScope = { _, query -> selected = query })
                }
            }
        }
        compose.onNodeWithTag("report_section_members").performClick()
        compose.onNodeWithTag("report_list").performScrollToNode(hasTestTag("report_member_income_0"))
        compose.onNodeWithTag("report_member_income_0").performClick()
        compose.onNodeWithTag("analyze_detail_in_chat").performClick()
        compose.onNodeWithText("生成解读").performClick()
        compose.runOnIdle {
            assertEquals("老婆", selected!!.member)
            assertEquals("INCOME", selected!!.type)
            assertEquals("2026-11-01", selected!!.start)
            assertEquals("2026-12-01", selected!!.end)
        }
    }

    @Test fun savedChatQueryExposesDetailsActionAfterRestoration() {
        val query = ChatQuery("2026-11-01", "2026-12-01", "老婆", "", "", type = "EXPENSE")
        val message = ChatMessage("answer", false, "已计算本月开支。", ConversationMemory.context("已计算本月开支。", listOf(query)))
        var opened: ChatMessage? = null
        compose.setContent {
            FamilyLedgerTheme {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    ChatTimeline(listOf(message), "fixture", "老公", "man", onQueryDetails = { opened = it })
                }
            }
        }
        compose.onNodeWithTag("chat_details:answer").performClick()
        compose.runOnIdle { assertEquals(listOf(query), ConversationMemory.queries(requireNotNull(opened))) }
    }

    @Test fun categoryAnalysisPreservesPrimaryCategoryWhenNamesOverlap() {
        var selected: ChatQuery? = null
        val old = row("old-salary", 10000, "", "老婆").copy(type = EntryType.INCOME, categoryL1 = "工资")
        val current = old.copy(id = "new-salary", amountMinor = 20000, categoryL1 = "职业收入", categoryL2 = "工资")
        compose.setContent {
            FamilyLedgerTheme {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    ReportContent(listOf(old, current), YearMonth.of(2026, 11), {}, false, false, null, null,
                        { _, _ -> fail("Expected category analysis") }, {}, {},
                        onAnalyzeScope = { _, query -> selected = query })
                }
            }
        }
        compose.onNodeWithTag("report_section_categories").performClick()
        compose.onNodeWithTag("report_list").performScrollToNode(hasTestTag("report_income_source_1"))
        compose.onNodeWithTag("report_income_source_1").performClick()
        compose.onNodeWithTag("analyze_detail_in_chat").performClick()
        compose.onNodeWithText("生成解读").performClick()
        compose.runOnIdle {
            val query = requireNotNull(selected)
            assertEquals("工资", query.category)
            assertEquals("L1", query.categoryLevel)
            assertEquals(listOf(old), ChatCodec.select(query, listOf(old, current)))
        }
    }

    @Test fun carAnswerFitsThreeThemesWithNarrowLargeText() {
        var theme by mutableStateOf(AppTheme.WARM)
        val query = ChatQuery("2026-01-01", "2026-11-12", "老公", "", "", label = "今年养车", type = "EXPENSE", topic = "private_car")
        val answer = "今年已记录养车支出 ¥ 230.00，共 2 笔。油费 ¥ 200.00，停车费 ¥ 30.00。另有 1 笔交通费待核对。"
        val messages = listOf(ChatMessage("question", true, "我今年养车花了多少钱，不包括公共交通？"),
            ChatMessage("answer", false, answer, ConversationMemory.context(answer, listOf(query))))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                FamilyLedgerTheme(theme) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        ChatTimeline(messages, "fixture", "老公", "man", modifier = Modifier.widthIn(max = 320.dp).fillMaxHeight(),
                            onQueryDetails = {})
                    }
                }
            }
        }
        AppTheme.entries.forEach { selected ->
            compose.runOnIdle { theme = selected }
            compose.onNodeWithTag("chat_details:answer").assertIsDisplayed()
            compose.waitForIdle()
            val instrument = InstrumentationRegistry.getInstrumentation()
            val file = File(instrument.targetContext.getExternalFilesDir(null), "ledger-screens/v1100-${selected.storageId}-car-chat.png")
            file.parentFile?.mkdirs()
            val bitmap = instrument.uiAutomation.takeScreenshot()
            FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            instrument.uiAutomation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/ledger-screens/${file.name}")
                .let { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
        }
    }
}
