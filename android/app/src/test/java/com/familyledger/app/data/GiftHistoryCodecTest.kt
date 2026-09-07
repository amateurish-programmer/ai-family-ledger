package com.familyledger.app.data

import com.familyledger.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GiftHistoryCodecTest {
    private fun entry(id: String = "00000000-0000-0000-0000-000000000001") = LedgerEntry(id, EntryType.EXPENSE,
        "2026-09-07", 12345, "人情往来", "随礼", "银行卡", "本人", "本人", note = "给小王随礼", updatedAt = 1)
    private fun result(id: String, name: Any = "小王", gift: Any = true) = JSONObject().put("items", JSONArray().put(
        JSONObject().put("id", id).put("isGift", gift).put("counterparty", name))).toString()
    @Test fun requestContainsOnlyIdentityMerchantNote() {
        val row = entry()
        val input = GiftHistoryCodec.input(listOf(row)).getJSONArray("items").getJSONObject(0)
        assertEquals(setOf("id", "merchant", "note"), input.keys().asSequence().toSet())
        assertEquals(row.id, input.getString("id"))
    }
    @Test fun proposalPreservesOriginalAndBlankCounterpartyRemainsPending() {
        val row = entry()
        val proposal = GiftHistoryCodec.decode(result(row.id, ""), listOf(row)).single()
        assertEquals(row, proposal.original); assertTrue(proposal.selected); assertEquals("", proposal.counterparty)
        assertFalse(row.isGift)
    }
    @Test fun untrustedResponsesCannotAddIdsFieldsOrCoerceTypes() {
        val row = entry()
        val bad = listOf(result("other"), result(row.id, 5), result(row.id, "小王", "true"),
            result(row.id, "小王", false), "{\"items\":[]}",
            JSONObject(result(row.id)).apply { getJSONArray("items").getJSONObject(0).put("amount", "9") }.toString(),
            JSONObject(result(row.id)).apply { getJSONArray("items").put(getJSONArray("items").get(0)) }.toString())
        bad.forEach { text -> assertThrows(Exception::class.java) { GiftHistoryCodec.decode(text, listOf(row)) } }
    }
    @Test fun categoryPairsAndEligibilityDoNotUseAmountOrMember() {
        val row = entry()
        val rows = listOf(row, row.copy(id="2",categoryL1="其他",categoryL2="随礼"),row.copy(id="3",deletedAt=2),
            row.copy(id="4",type=EntryType.BALANCE_ADJUSTMENT),row.copy(id="5",isGift=true,counterparty="小王"),
            row.copy(id="6",isGift=true,counterparty=""))
        val category = GiftCategory("人情往来", "随礼")
        assertTrue(category.suggested)
        assertEquals(listOf("00000000-0000-0000-0000-000000000001", "6"), GiftHistoryCodec.candidates(rows,setOf(category)).map { it.id })
        assertEquals(2, GiftHistoryCodec.categories(rows).size)
    }
    @Test fun chineseNotesSplitWithinUtf8EnvelopeLimit() {
        val rows = List(20) { entry("00000000-0000-0000-0000-" + it.toString().padStart(12, '0')).copy(note = "中".repeat(2000), merchant = "商".repeat(100)) }
        val batches = GiftHistoryCodec.batches(rows)
        assertTrue(batches.size > 1)
        assertEquals(rows, batches.flatten())
        batches.forEach { batch ->
            assertTrue(batch.size <= 20)
            assertTrue(JSONObject().put("operation", "gift_history").put("input", GiftHistoryCodec.input(batch)).toString().toByteArray(Charsets.UTF_8).size <= 60000)
        }
    }
    @Test fun batchingBoundAndDuplicateInputsRejected() {
        assertThrows(IllegalArgumentException::class.java) { GiftHistoryCodec.input(List(21) { entry(it.toString()) }) }
        assertThrows(IllegalArgumentException::class.java) { GiftHistoryCodec.input(listOf(entry(),entry())) }
    }
    @Test fun chatGiftDraftAndLegacyDraftBothDecodeWithoutSaving() {
        val old = JSONObject().put("type","EXPENSE").put("amount","100.00").put("date","2026-09-07")
            .put("category","人情").put("subcategory","").put("account","现金").put("member","本人")
            .put("recordedBy","本人").put("merchant","").put("project","").put("note","给小王随礼100元")
        fun decode() = QuickEntryCodec.cloud(JSONObject().put("entries",JSONArray().put(old)).toString()).single()
        assertFalse(decode().isGift)
        old.put("isGift",true).put("counterparty","小王")
        val gift=decode(); assertTrue(gift.isGift);assertEquals("小王",gift.counterparty);assertEquals(10000L,gift.amountMinor)
        old.put("isGift","true")
        assertThrows(Exception::class.java) { decode() }
    }
}
