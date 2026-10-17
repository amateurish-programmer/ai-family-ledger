package com.familyledger.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.familyledger.app.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppearanceUiTest {
    @get:Rule val compose = createComposeRule()

    /** Catches a picker that changes its highlight but never saves the selected preference. */
    @Test fun themeChoiceAppliesImmediatelyAndSurvivesNewPreferenceInstance() = withRestoredAppearance { context ->
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().remove("theme").commit()
        val preferences = AppearancePreferences(context)
        compose.setContent {
            val theme by preferences.theme.collectAsState()
            FamilyLedgerTheme(theme, preferences::select) {
                Column(Modifier.verticalScroll(rememberScrollState())) { ThemePicker() }
            }
        }
        compose.onNodeWithTag("theme_warm").assertIsSelected()
        compose.onNodeWithTag("theme_journal").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(AppTheme.JOURNAL, AppearancePreferences(context).theme.value) }
        compose.onNodeWithTag("theme_finance").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(AppTheme.FINANCE, AppearancePreferences(context).theme.value) }
        compose.onNodeWithTag("theme_warm").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(AppTheme.WARM, AppearancePreferences(context).theme.value) }
    }

    /** Catches an invalid stored preference preventing the app's default appearance from opening. */
    @Test fun missingAndInvalidStoredThemeFallBackToWarm() = withRestoredAppearance { context ->
        val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        preferences.edit().remove("theme").commit()
        assertEquals(AppTheme.WARM, AppearancePreferences(context).theme.value)
        preferences.edit().putString("theme", "obsolete-theme").commit()
        assertEquals(AppTheme.WARM, AppearancePreferences(context).theme.value)
        preferences.edit().putInt("theme", 42).commit()
        assertEquals(AppTheme.WARM, AppearancePreferences(context).theme.value)
    }

    /** Catches secondary navigation or state restoration discarding an unsaved local role. */
    @Test fun roleDraftAndSecondaryRouteSurviveRestorationAndHomeNavigation() = withSettingsModel { model ->
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FamilyLedgerTheme { SettingsScreen(model, LedgerState(loading = false, localRole = "本人")) } }
        compose.onNodeWithText("角色设置").performScrollTo().performClick()
        compose.onNodeWithText("角色名称").performScrollTo().performTextReplacement("测试家人")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assert(hasText("测试家人"))
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithText("角色名称").assertDoesNotExist()
        compose.onNodeWithText("角色设置").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).assert(hasText("测试家人"))
        compose.onNodeWithText("管理家庭账号与同步").performScrollTo().assertIsDisplayed()
    }

    /** Catches update notifications opening the home menu or repeating their consumption callback. */
    @Test fun updateFocusOpensUpdatesAndIsConsumedOnce() = withSettingsModel { model ->
        var focused by mutableStateOf(true)
        var unrelated by mutableStateOf(false)
        var consumed = 0
        compose.setContent { FamilyLedgerTheme {
            SettingsScreen(model, LedgerState(loading = false, syncing = unrelated), focused) {
                consumed++
                focused = false
            }
        } }
        compose.onNodeWithText("检查更新").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { unrelated = true }
        compose.runOnIdle { assertEquals(1, consumed) }
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithText("角色设置").assertExists()
    }

    private fun withSettingsModel(block: (LedgerViewModel) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val store = ViewModelStore()
        lateinit var model: LedgerViewModel
        compose.runOnIdle { model = LedgerViewModel(LedgerRepository(database)); store.put("appearance-test", model) }
        try { block(model) } finally { compose.runOnIdle { store.clear() }; database.close() }
    }

    private fun withRestoredAppearance(block: (Context) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        val old = preferences.all["theme"]
        try { block(context) } finally {
            preferences.edit().apply {
                when (old) {
                    null -> remove("theme")
                    is String -> putString("theme", old)
                    is Int -> putInt("theme", old)
                    is Long -> putLong("theme", old)
                    is Float -> putFloat("theme", old)
                    is Boolean -> putBoolean("theme", old)
                    is Set<*> -> putStringSet("theme", old.filterIsInstance<String>().toSet())
                }
            }.commit()
        }
    }
}
