package com.familyledger.app.ui

import android.content.Context
import androidx.room.Room
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.familyledger.app.data.*
import com.familyledger.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GiftHistoryViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun row() = LedgerEntry("00000000-0000-0000-0000-000000000001", EntryType.EXPENSE, "2026-09-07", 10000,
        "人情", "随礼", "现金", "本人", "本人", note="给小王随礼", updatedAt=1)
    private fun response(input: JSONObject) = JSONObject().put("items", JSONArray().apply {
        val items = input.getJSONArray("items")
        for (i in 0 until items.length()) put(JSONObject().put("id",items.getJSONObject(i).getString("id")).put("isGift",true).put("counterparty","小王"))
    }).toString()
    @Test fun cancellationFailureRetryAndExplicitConfirmation() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,LedgerDatabase::class.java).build()
        val repo=LedgerRepository(db);repo.save(row())
        val original=repo.allEntries().single()
        var gate=CompletableDeferred<Unit>();var entered=CompletableDeferred<Unit>();var fail=false
        val model=LedgerViewModel(repo,giftHistoryClient=GiftHistoryClient({"fixture"},{ input ->
            entered.complete(Unit);gate.await();if(fail) error("合成失败");response(input)
        }))
        try {
            withTimeout(5000) {model.state.first {!it.loading}}
            main {model.openGiftHistory();model.analyzeGiftHistory()}
            withTimeout(5000) {entered.await()}
            assertTrue(model.state.value.busy)
            main {model.closeGiftHistory()};assertTrue(model.state.value.giftHistoryOpen)
            main {model.cancelGiftAnalysis()}
            withTimeout(5000) {model.state.first {!it.busy}}
            assertNull(model.state.value.giftProposals);assertEquals(original,repo.allEntries().single())
            entered=CompletableDeferred();gate=CompletableDeferred();fail=true
            main {model.analyzeGiftHistory()};withTimeout(5000) {entered.await()};gate.complete(Unit)
            withTimeout(5000) {model.state.first {!it.busy}}
            assertNull(model.state.value.giftProposals);assertEquals(original,repo.allEntries().single())
            entered=CompletableDeferred();gate=CompletableDeferred();fail=false
            main {model.analyzeGiftHistory()};withTimeout(5000) {entered.await()};gate.complete(Unit)
            withTimeout(5000) {model.state.first {!it.busy && it.giftProposals != null}}
            assertEquals(original,repo.allEntries().single())
            main {model.updateGiftProposal(original.id,true,"");model.confirmGiftHistory()}
            withTimeout(5000) {model.state.first {!it.busy && !it.giftHistoryOpen}}
            val saved=repo.allEntries().single();assertTrue(saved.isGift);assertEquals("",saved.counterparty)
            assertEquals(original,saved.copy(isGift=false,counterparty="",updatedAt=original.updatedAt))
        } finally {gate.complete(Unit);main {model.viewModelScope.cancel()};db.close()}
    }
    @Test fun accountChangeRejectsConfirmationWithoutWriting() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,LedgerDatabase::class.java).build()
        val repo=LedgerRepository(db);repo.save(row());val original=repo.allEntries().single()
        var owner="first"
        val model=LedgerViewModel(repo,giftHistoryClient=GiftHistoryClient({owner},::response))
        try {
            withTimeout(5000) {model.state.first {!it.loading}}
            main {model.openGiftHistory();model.analyzeGiftHistory()}
            withTimeout(5000) {model.state.first {!it.busy && it.giftProposals != null}}
            owner="second";main {model.confirmGiftHistory()}
            withTimeout(5000) {model.state.first {!it.busy}}
            assertEquals(original,repo.allEntries().single());assertTrue(model.state.value.message.orEmpty().contains("账号已改变"))
        } finally {main {model.viewModelScope.cancel()};db.close()}
    }
}
