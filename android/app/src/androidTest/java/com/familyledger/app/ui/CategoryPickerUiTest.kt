package com.familyledger.app.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.familyledger.app.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CategoryPickerUiTest {
    @get:Rule val compose = createComposeRule()

    private fun entry() = LedgerEntry("edit", EntryType.EXPENSE, "2026-11-11", 3680L,
        "历史分类", "家庭自定子类", "微信", "成员甲", "操作者乙", merchant = "合成商家", project = "合成项目",
        note = "保留备注", updatedAt = 123L, isGift = true, counterparty = "对象甲",
        origin = ImportOrigin("a".repeat(64), "合成表.xlsx", "支出", 2, 10, "2026/11/11"))

    @Test fun openingAndSavingKeepsLegacyPairMoneySourceAndGiftMetadata() {
        val original = entry()
        var saved: LedgerEntry? = null
        compose.setContent { FamilyLedgerTheme {
            EntryEditor(original, false, SnackbarHostState(), {}, { saved = it })
        } }
        compose.onNodeWithTag("category_l1_picker").performScrollTo().assertTextContains("历史分类")
        compose.onNodeWithTag("category_l2_picker").performScrollTo().assertTextContains("家庭自定子类")
        compose.onNodeWithText("保存记录").performClick()
        compose.runOnIdle { assertEquals(original, saved) }
    }

    @Test fun choosingAnotherParentClearsThePreviousChild() {
        var pair = "私家车" to "油费"
        compose.setContent { FamilyLedgerTheme {
            var selected by remember { mutableStateOf(pair) }
            CategoryPicker(EntryType.EXPENSE, selected.first, selected.second) { parent, child ->
                selected = parent to child
                pair = selected
            }
        } }
        compose.onNodeWithTag("category_l1_picker").performClick()
        compose.onNodeWithText("公共出行", useUnmergedTree = true).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("公共出行" to "", pair) }
        compose.onNodeWithTag("category_l2_picker").performClick()
        compose.onNodeWithText("出租网约车", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals("公共出行" to "出租网约车", pair) }
    }

    @Test fun explicitNewCategoryActionsAllowCustomFamilyPairs() {
        var pair = "食品酒水" to "午餐"
        compose.setContent { FamilyLedgerTheme {
            var selected by remember { mutableStateOf(pair) }
            CategoryPicker(EntryType.EXPENSE, selected.first, selected.second) { parent, child ->
                selected = parent to child
                pair = selected
            }
        } }
        compose.onNodeWithTag("category_l1_picker").performClick()
        compose.onNodeWithText("新增一级分类").performClick()
        compose.onNodeWithText("新一级分类").performTextInput("家庭宠物")
        compose.onNodeWithText("添加并使用").performClick()
        compose.onNodeWithTag("category_l2_picker").performClick()
        compose.onNodeWithText("新增二级分类").performClick()
        compose.onNodeWithText("新二级分类").performTextInput("寄养")
        compose.onNodeWithText("添加并使用").performClick()
        compose.runOnIdle { assertEquals("家庭宠物" to "寄养", pair) }
    }

    @Test fun changingEntryTypeChoosesAValidParentAndClearsTheChild() {
        var saved: LedgerEntry? = null
        compose.setContent { FamilyLedgerTheme {
            EntryEditor(entry().copy(categoryL1 = "私家车", categoryL2 = "油费"), false,
                SnackbarHostState(), {}, { saved = it })
        } }
        compose.onNodeWithText("收入").performScrollTo().performClick()
        compose.onNodeWithText("保存记录").performClick()
        compose.runOnIdle {
            assertEquals(EntryType.INCOME, saved?.type)
            assertEquals("职业收入", saved?.categoryL1)
            assertEquals("", saved?.categoryL2)
            assertEquals(3680L, saved?.amountMinor)
            assertEquals(entry().origin, saved?.origin)
        }
    }
}
