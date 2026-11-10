package com.familyledger.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.familyledger.app.data.*
import com.familyledger.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.YearMonth
import java.util.Base64

class FamilyAvatarUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedPhotoRendersInChatAndAsAnotherFamilyMembersIncomeAvatar() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        val local = LocalAvatarImages.write(context, bitmap)
        bitmap.recycle()
        val file = requireNotNull(LocalAvatarImages.file(context, local))
        val cache = FamilyAvatarCache(context, "fixture-recipient", "fixture-family")
        val photo = JSONObject().put("user_id", "fixture-sender").put("role", "甲").put("avatar", "photo")
            .put("photo", Base64.getEncoder().encodeToString(file.readBytes()))
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val store = ViewModelStore()
        try {
            cache.replace(JSONArray().put(photo))
            var shared by mutableStateOf(FamilyAvatarCache(context, "fixture-recipient", "fixture-family").read())
            var viewer by mutableStateOf("乙")
            assertNotNull(FamilyAvatarCache.file(context, shared.getValue("甲")))
            assertTrue(FamilyAvatarCache(context, "fixture-recipient", "another-family").read().isEmpty())
            var report by mutableStateOf(false)
            lateinit var model: LedgerViewModel
            compose.runOnIdle { model = LedgerViewModel(LedgerRepository(db)); store.put("avatar", model) }
            compose.setContent {
                FamilyLedgerTheme {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        if (!report) QuickEntryScreen(model, LedgerState(loading = false, localRole = "甲", localAvatar = local,
                            conversationLoad = ConversationLoad.READY), SnackbarHostState(), null, {}, "fixture")
                        else ReportsScreen(listOf(LedgerEntry("one", EntryType.INCOME, "2026-11-01", 125L, "工资",
                            account = "现金", member = "甲", recordedBy = "乙")), YearMonth.of(2026, 11), {}, model,
                            LedgerState(loading = false, localRole = viewer, localAvatar = local, memberAvatars = shared))
                    }
                }
            }
            assertMagentaPhoto()
            compose.runOnIdle { report = true }
            compose.onNodeWithTag("report_section_members").performClick()
            compose.onNodeWithTag("report_list").performScrollToNode(hasText("甲"))
            assertMagentaPhoto(scrollIntoView = true)
            cache.replace(JSONArray().put(photo).put(JSONObject().put("role", "甲").put("avatar", "cat")))
            assertEquals(FamilyAvatarCache.AMBIGUOUS, cache.read("甲")["甲"])
            compose.runOnIdle { viewer = "甲"; shared = cache.read("甲") }
            compose.onNodeWithContentDescription("自定义角色头像", useUnmergedTree = true).assertDoesNotExist()
        } finally {
            compose.runOnIdle { store.clear() }
            db.close()
            cache.replace(JSONArray())
            file.delete()
        }
    }

    private fun assertMagentaPhoto(scrollIntoView: Boolean = false) {
        val image = hasContentDescription("自定义角色头像")
        compose.waitUntil(5000) { compose.onAllNodes(image, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNode(image, useUnmergedTree = true)
        if (scrollIntoView) node.performScrollTo()
        node.assertIsDisplayed()
        compose.waitUntil(5000) {
            val pixels = node.captureToImage().toPixelMap()
            val center = pixels[pixels.width / 2, pixels.height / 2]
            center.red > .9f && center.blue > .9f && center.green < .1f
        }
    }
}
