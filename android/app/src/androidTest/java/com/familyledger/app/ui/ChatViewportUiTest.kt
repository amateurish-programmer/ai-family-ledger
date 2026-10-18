package com.familyledger.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.familyledger.app.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor

/** Pixel checks use synthetic content, including deliberate drawing outside a lazy item's bounds. */
class ChatViewportUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun timelineClipsOverflowToAllFourEdgesAcrossThemes() {
        var theme by mutableStateOf(AppTheme.WARM)
        lateinit var list: LazyListState
        lateinit var scope: CoroutineScope
        compose.setContent {
            FamilyLedgerTheme(theme) {
                Box(Modifier.size(280.dp, 380.dp).background(sentinel).testTag("viewport_root"),
                    contentAlignment = Alignment.Center) {
                    list = rememberLazyListState()
                    scope = rememberCoroutineScope()
                    ChatTimeline(listOf(ChatMessage("message", false, "synthetic message")), "clip-test", "本人", "person",
                        modifier = Modifier.size(200.dp, 240.dp).background(Color.White), listState = list,
                        latestContent = {
                            item(key = "overflow-probe") {
                                Box(Modifier.fillMaxWidth().height(1200.dp).drawBehind {
                                    // Foundation permits cross-axis shadows outside its default clip.
                                    // The chat viewport must contain even deliberately overflowing pixels.
                                    drawRect(probe, Offset(-1000.dp.toPx(), -1000.dp.toPx()),
                                        Size(size.width + 2000.dp.toPx(), size.height + 2000.dp.toPx()))
                                })
                            }
                        })
                }
            }
        }
        AppTheme.entries.forEach { choice ->
            compose.runOnIdle { theme = choice }
            listOf(0, 83).forEach { offset ->
                compose.runOnIdle { scope.launch { list.scrollToItem(0, offset) } }
                compose.waitForIdle()
                val root = compose.onNodeWithTag("viewport_root")
                val rootBounds = root.fetchSemanticsNode().boundsInRoot
                val listBounds = compose.onNodeWithTag("chat_timeline").fetchSemanticsNode().boundsInRoot
                val pixels = root.captureToImage().toPixelMap()
                assertOutsideIsSentinel(pixels, relativeTo(listBounds, rootBounds), "$choice offset=$offset")
                assertEquals("The overflow probe must actually be visible inside the viewport", probe,
                    pixels[pixels.width / 2, pixels.height / 2])
            }
        }
    }

    @Test fun longChatPreservesHeaderPixelsAndPageBoundaryAcrossThemes() {
        val database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            LedgerDatabase::class.java).build()
        val store = ViewModelStore()
        lateinit var model: LedgerViewModel
        var theme by mutableStateOf(AppTheme.WARM)
        val state = LedgerState(loading = false, localRole = "测试成员", localAvatar = "person",
            cloudStatus = CloudStatus(true, "viewport@example.invalid", "synthetic-family", "测试家庭", 0),
            chatMessages = (1..60).map { index ->
                ChatMessage("message-$index", index % 2 == 0,
                    "第 $index 条合成聊天。" + "这是一段用于滚动边界验证的长消息。".repeat(24))
            })
        try {
            compose.runOnIdle { model = LedgerViewModel(LedgerRepository(database)); store.put("viewport", model) }
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    FamilyLedgerTheme(theme) {
                        Box(Modifier.fillMaxSize().background(sentinel).testTag("page_root")) {
                            Box(Modifier.fillMaxSize().padding(16.dp).testTag("page_viewport")) {
                                QuickEntryScreen(model, state, SnackbarHostState(), null, {}, "viewport-test")
                            }
                        }
                    }
                }
            }
            AppTheme.entries.forEach { choice ->
                compose.runOnIdle { theme = choice }
                compose.onNodeWithTag("chat_timeline").performScrollToIndex(0)
                compose.waitForIdle()
                val root = compose.onNodeWithTag("page_root")
                val rootBounds = root.fetchSemanticsNode().boundsInRoot
                val headerBounds = relativeTo(compose.onNodeWithTag("chat_header").fetchSemanticsNode().boundsInRoot, rootBounds)
                val pageBounds = relativeTo(compose.onNodeWithTag("page_viewport").fetchSemanticsNode().boundsInRoot, rootBounds)
                val before = root.captureToImage().toPixelMap()
                listOf(8, 15, 0).forEach { index ->
                    compose.onNodeWithTag("chat_timeline").performScrollToIndex(index)
                    compose.waitForIdle()
                    val after = root.captureToImage().toPixelMap()
                    assertRegionEquals(before, after, headerBounds, "$choice header after scroll to $index")
                    assertOutsideIsSentinel(after, pageBounds, "$choice page after scroll to $index")
                }
            }
        } finally {
            compose.runOnIdle { store.clear() }
            database.close()
        }
    }

    private fun relativeTo(bounds: Rect, root: Rect) = Rect(bounds.left - root.left, bounds.top - root.top,
        bounds.right - root.left, bounds.bottom - root.top)

    private fun assertOutsideIsSentinel(pixels: PixelMap, content: Rect, label: String) {
        var checked = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            // Leave one pixel along each edge for fractional layout/antialias rounding.
            if (x + 1f < content.left || x - 1f >= content.right || y + 1f < content.top || y - 1f >= content.bottom) {
                assertEquals("$label leaked at ($x, $y)", sentinel, pixels[x, y])
                checked++
            }
        }
        assertTrue("$label needs a visible exterior sentinel region", checked > 0)
    }

    private fun assertRegionEquals(before: PixelMap, after: PixelMap, bounds: Rect, label: String) {
        for (y in ceil(bounds.top).toInt() until floor(bounds.bottom).toInt()) {
            for (x in ceil(bounds.left).toInt() until floor(bounds.right).toInt()) {
                assertEquals("$label changed at ($x, $y)", before[x, y], after[x, y])
            }
        }
    }

    private companion object {
        val sentinel = Color(0xFF123456)
        val probe = Color(0xFFFF00FF)
    }
}
