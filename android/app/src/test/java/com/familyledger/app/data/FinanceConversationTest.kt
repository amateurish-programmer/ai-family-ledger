package com.familyledger.app.data

import com.familyledger.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FinanceConversationTest {
    private val today = LocalDate.parse("2026-10-17")
    private val queryResponse = """{"reply":"正在比较","entries":[],"query":null,"queries":[{"start":"2026-09-01","end":"2026-10-01","member":"","category":"","keyword":"","label":"九月","type":"EXPENSE","giftOnly":false,"counterparty":""},{"start":"2026-10-01","end":"2026-11-01","member":"","category":"","keyword":"","label":"十月","type":"EXPENSE","giftOnly":false,"counterparty":""}]}"""

    @Test fun planningAndAnalysisShareBoundedMemoryAndUseAllQueries() = runBlocking {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        val messages = listOf(ChatMessage("goal", true, "我的目标是攒钱买房", "我的目标是攒钱买房"))
        val result = FinanceConversation.respond("比较两个月的花费", today, "老婆", messages, emptyList(), "未同步",
            ai = { operation, input ->
                calls += operation to input
                if (operation == "chat") queryResponse else "十月尚未结束，请结合相同天数再比较。"
            })
        assertEquals(listOf("chat", "analyze"), calls.map { it.first })
        assertEquals(2, result.queries.size)
        assertTrue(result.entries.isEmpty())
        calls.forEach { assertTrue(it.second.getBoolean("analysisV2")); assertTrue(it.second.has("memory")) }
        assertEquals("老婆", calls.first().second.getString("role"))
        val facts = calls.last().second.getJSONObject("finance").getJSONArray("facts").toString()
        assertTrue(facts.contains("Q1")); assertTrue(facts.contains("Q2"))
        assertEquals("十月尚未结束，请结合相同天数再比较。", result.reply)
    }

    @Test fun analysisFailureReturnsComputedResultsWithoutMakingDrafts() = runBlocking {
        val result = FinanceConversation.respond("比较", today, "本人", emptyList(), emptyList(), "未同步",
            ai = { operation, _ -> if (operation == "chat") queryResponse else error("timeout") })
        assertTrue(result.reply.contains("程序计算"))
        assertTrue(result.reply.contains("九月")); assertTrue(result.reply.contains("十月"))
        assertTrue(result.reply.contains("0.00"))
        assertTrue(result.entries.isEmpty())
    }

    @Test fun cancellationIsNeverConvertedIntoAnAnswer() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                FinanceConversation.respond("比较", today, "本人", emptyList(), emptyList(), "未同步",
                    ai = { operation, _ -> if (operation == "chat") queryResponse else throw CancellationException("cancel") })
            }
        }
    }

    @Test fun longChineseHistoryFitsByteBudgetWithoutLosingCurrentQuestionOrFacts() {
        val facts = JSONArray((0 until 145).map { JSONObject().put("id", "F$it").put("label", "分类".repeat(28)).put("value", "¥ 123456789.01") })
        val source = JSONObject().put("text", "这笔具体的当前问题".repeat(150))
            .put("finance", JSONObject().put("facts", facts))
            .put("history", JSONArray((0 until 8).map { JSONObject().put("role", "user").put("text", "中文历史内容".repeat(250)) }))
            .put("memory", JSONArray((0 until 8).map { "过去的用户偏好".repeat(70) }))
        assertTrue(source.toString().toByteArray(Charsets.UTF_8).size > 65536)
        val bounded = FinanceConversation.boundedInput("chat", source)
        assertTrue(JSONObject().put("operation", "chat").put("input", bounded).toString().toByteArray(Charsets.UTF_8).size < 65536)
        assertEquals(source.getString("text"), bounded.getString("text"))
        assertEquals(facts.toString(), bounded.getJSONObject("finance").getJSONArray("facts").toString())
        assertEquals(1500, source.getJSONArray("history").getJSONObject(0).getString("text").length)
    }
}
