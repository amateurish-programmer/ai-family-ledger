package com.familyledger.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.familyledger.app.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AvatarPickerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectionIsOnlyAPreviewAndCancelOrCorruptInputPreservesItUntilSave() = withIsolatedAvatarProfile { profile ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("avatar-test-", ".png", File(context.cacheDir, "network-diagnostics").apply { mkdirs() })
        val sourceUri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", source)
        val registry = object : ActivityResultRegistry() {
            var launched = 0
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) { launched = requestCode }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val store = ViewModelStore()
        val cloud = CloudService(profile)
        lateinit var model: LedgerViewModel
        fun pick(result: Int, data: Intent?) {
            compose.onNodeWithText("从相册选择图片").performScrollTo().performClick()
            compose.runOnIdle {
                assertTrue(model.state.value.documentPickerOpen)
                assertTrue(registry.dispatchResult(registry.launched, result, data))
            }
        }
        try {
            source.outputStream().use { Bitmap.createBitmap(280, 340, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
            compose.runOnIdle { model = LedgerViewModel(LedgerRepository(db), cloud); store.put("avatar", model) }
            compose.setContent {
                val state by model.state.collectAsState()
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalContext provides profile) {
                    FamilyLedgerTheme { SettingsScreen(model, state) }
                }
            }
            compose.waitUntil(5000) { !model.state.value.loading }
            compose.onNodeWithText("角色设置").performScrollTo().performClick()
            pick(Activity.RESULT_OK, Intent().setData(sourceUri))
            compose.waitUntil(5000) { model.state.value.avatarPreview != null && !model.state.value.busy }
            val preview = model.state.value.avatarPreview
            compose.onNodeWithContentDescription("待保存的图片头像").assertExists()
            assertEquals("person", cloud.localAvatar())
            pick(Activity.RESULT_CANCELED, null)
            assertSame(preview, model.state.value.avatarPreview)
            source.writeText("broken")
            pick(Activity.RESULT_OK, Intent().setData(sourceUri))
            compose.waitUntil(5000) { model.state.value.message != null && !model.state.value.busy }
            assertSame(preview, model.state.value.avatarPreview)
            assertEquals("person", cloud.localAvatar())
            compose.onNodeWithText("保存角色").performScrollTo().performClick()
            compose.waitUntil(5000) { model.state.value.avatarPreview == null && !model.state.value.busy }
            val saved = CloudService(profile).localAvatar()
            assertNotNull(LocalAvatarImages.file(profile, saved))
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("自定义角色头像").fetchSemanticsNodes().isNotEmpty() }
        } finally { compose.runOnIdle { store.clear() }; db.close(); source.delete() }
    }
}
