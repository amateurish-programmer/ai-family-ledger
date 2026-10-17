package com.familyledger.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ChatV2Test {
    private val today = LocalDate.of(2026, 10, 17)
    private fun query() = JSONObject().put("start", "2026-09-01").put("end", "2026-10-01")
        .put("member", "老婆").put("category", "").put("keyword", "").put("label", "老婆收入")
        .put("type", "INCOME").put("giftOnly", false).put("counterparty", "")
    private fun response(queries: JSONArray) = JSONObject().put("reply", "正在查询")
        .put("entries", JSONArray()).put("query", JSONObject.NULL).put("queries", queries).toString()

    @Test fun acceptsBoundedV2QueriesWithoutCreatingEntries() {
        val result = ChatCodec.decode(response(JSONArray().put(query()).put(query().put("member", "老公"))), "老公")
        assertTrue(result.entries.isEmpty())
        assertNull(result.query)
        assertEquals(2, result.queries.size)
        assertEquals("老婆", result.queries.first().member)
        assertEquals("INCOME", result.queries.first().type)
        assertEquals("老婆收入", result.queries.first().label)
    }

    @Test fun rejectsWrongTypesUnknownFieldsAndTooManyQueries() {
        val invalid = listOf(query().put("giftOnly", "false"), query().put("type", "BALANCE_ADJUSTMENT"),
            query().put("label", "x".repeat(101)), query().put("member", "老\n婆"), query().put("sql", "select"))
        invalid.forEach { q -> assertThrows(IllegalArgumentException::class.java) { ChatCodec.decode(response(JSONArray().put(q)), "老公") } }
        assertThrows(IllegalArgumentException::class.java) {
            ChatCodec.decode(response(JSONArray(List(5) { query() })), "老公")
        }
    }

    @Test fun offlineAmbiguousThirdPartyGiftOrUnrealizedStatementsNeverCreateDrafts() {
        listOf("老婆收入1034元", "我收到红包200元", "明天工资到账5000元", "还没收到工资5000元", "假如收入10000元", "张三收入200元", "计划午饭36元").forEach { text ->
            assertThrows(text, IllegalArgumentException::class.java) { ChatCodec.localFallback(text, today, "老公") }
        }
    }

    @Test fun offlineSimpleOwnTransactionDoesNotInventAnAccount() {
        val entry = ChatCodec.localFallback("今天午饭36元", today, "老婆").entries.single()
        assertEquals("未指定", entry.account)
        assertEquals("老婆", entry.member)
        assertEquals("老婆", entry.recordedBy)
    }
    @Test fun legacyQueriesRemainReadableAndMixedEntryQueriesAreRejected() {
        val old = query().apply { listOf("label", "type", "giftOnly", "counterparty").forEach(::remove) }
        val root = JSONObject().put("reply", "查询").put("entries", JSONArray()).put("query", old)
        val decoded = ChatCodec.decode(root.toString(), "老婆")
        assertEquals("老婆", decoded.query!!.member)
        assertEquals("", decoded.query.type)
        assertTrue(decoded.queries.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            ChatCodec.decode(root.put("queries", JSONArray().put(query())).toString(), "老婆")
        }
    }
    @Test fun localFallbackRetainsExplicitPaymentMethodAndRejectsFutureCalendarDate() {
        assertEquals("微信", ChatCodec.localFallback("我用微信支付午饭36元", today, "老婆").entries.single().account)
        assertThrows(IllegalArgumentException::class.java) { ChatCodec.localFallback("2026-10-18午饭36元", today, "老婆") }
    }
    @Test fun offlineQueryAnswerExplainsGiftAndDirectionScopeEvenWithNoRows() {
        val q = ChatQuery("2026-09-01", "2026-10-01", "老婆", "", "", type = "INCOME", giftOnly = true, counterparty = "张三")
        val answer = ChatCodec.answer(q, emptyList())
        assertTrue(answer.contains("收支类型：收入"))
        assertTrue(answer.contains("仅礼金"))
        assertTrue(answer.contains("往来人：张三"))
    }
}
