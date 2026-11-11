package com.familyledger.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.familyledger.app.domain.EntryType
import com.familyledger.app.domain.LedgerEntry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CategoryReviewUiTest {
    @get:Rule val compose = createComposeRule()
    private fun row(id: String) = LedgerEntry(id, EntryType.EXPENSE, "2026-11-10", 12345,
        "行车交通", "加油", "现金", "归属家人", "实际操作者", note = "合成$id", updatedAt = 10)

    @Test fun onlyExplicitlyConfirmedSelectedRowsAreApplied() {
        val first = row("first")
        val second = row("second")
        val ambiguous = row("ambiguous").copy(categoryL2 = "交通费")
        var applied: Pair<List<LedgerEntry>, List<LedgerEntry>>? = null
        compose.setContent { FamilyLedgerTheme {
            CategoryReviewScreen(listOf(first, second, ambiguous), false, {}, { before, after -> applied = before to after })
        } }
        compose.onNodeWithTag("category_review_select_ambiguous").assertDoesNotExist()
        compose.onNodeWithTag("category_review_select_first").assertIsOn().performClick()
        compose.onNodeWithTag("category_review_apply").performClick()
        compose.runOnIdle { assertNull(applied) }
        compose.onNodeWithTag("category_review_confirm").performClick()
        compose.runOnIdle {
            val (before, after) = applied!!
            assertEquals(listOf(second), before)
            assertEquals(1, after.size)
            assertEquals(second, after.single().copy(categoryL1 = second.categoryL1, categoryL2 = second.categoryL2))
            assertNotEquals(second.categoryL1 to second.categoryL2, after.single().categoryL1 to after.single().categoryL2)
        }
    }

    @Test fun changedOriginalInvalidatesOpenConfirmationAndSelection() {
        val original = row("changed")
        var entries by mutableStateOf(listOf(original))
        var calls = 0
        compose.setContent { FamilyLedgerTheme {
            CategoryReviewScreen(entries, false, {}, { _, _ -> calls++ })
        } }
        compose.onNodeWithTag("category_review_apply").performClick()
        compose.runOnIdle { entries = listOf(original.copy(note = "同步后的新备注", updatedAt = 20)) }
        compose.onNodeWithTag("category_review_confirm").assertIsNotEnabled()
        compose.onNodeWithText("继续核对").performClick()
        compose.onNodeWithTag("category_review_select_changed").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("category_review_apply").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun reorderingEntriesKeepsSelectionByRecordId() {
        val first = row("first")
        val second = row("second")
        var entries by mutableStateOf(listOf(first, second))
        var originals = emptyList<LedgerEntry>()
        compose.setContent { FamilyLedgerTheme {
            CategoryReviewScreen(entries, false, {}, { before, _ -> originals = before })
        } }
        compose.onNodeWithTag("category_review_select_first").performClick()
        compose.runOnIdle { entries = listOf(second, first) }
        compose.onNodeWithTag("category_review_select_first").assertIsOff()
        compose.onNodeWithTag("category_review_select_second").assertIsOn()
        compose.onNodeWithTag("category_review_apply").performClick()
        compose.onNodeWithTag("category_review_confirm").performClick()
        compose.runOnIdle { assertEquals(listOf(second), originals) }
    }

    @Test fun restoringScreenKeepsDeselectedRecordIds() {
        val restoration = StateRestorationTester(compose)
        val entries = listOf(row("first"), row("second"))
        restoration.setContent { FamilyLedgerTheme {
            CategoryReviewScreen(entries, false, {}, { _, _ -> })
        } }
        compose.onNodeWithTag("category_review_select_first").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("category_review_select_first").assertIsOff()
        compose.onNodeWithTag("category_review_select_second").assertIsOn()
    }
}
