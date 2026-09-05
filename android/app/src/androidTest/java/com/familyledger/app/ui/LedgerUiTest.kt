package com.familyledger.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.familyledger.app.data.*
import org.junit.*

class LedgerUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: LedgerDatabase

    @After fun close() { db.close() }

    @Test fun recordExpenseAndSeeItInLedger() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).build()
        val model = LedgerViewModel(LedgerRepository(db))
        compose.setContent { FamilyLedgerTheme { LedgerApp(model) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("记一笔").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("记一笔").performClick()
        compose.onNodeWithText("金额（元）").performTextInput("36.80")
        compose.onNodeWithText("保存记录").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("−36.80").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("−36.80").assertIsDisplayed()
        compose.onNodeWithText("报表").performClick()
        compose.onNodeWithText("支出去向").assertExists()
    }
}
