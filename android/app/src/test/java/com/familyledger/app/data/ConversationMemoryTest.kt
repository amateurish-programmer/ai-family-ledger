package com.familyledger.app.data

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ConversationMemoryTest {
    private fun history(messages: List<ChatMessage>) = ConversationMemory.history(messages)
    private fun memory(messages: List<ChatMessage>) = ConversationMemory.memory(messages)

    @Test fun historyRetainsLatestEightWithinPerMessageLimitAndReadsLegacyContext() {
        val messages = (0..12).map { ChatMessage("m$it", true, "消息$it" + "字".repeat(1700)) } +
            ChatMessage("old", false, "屏幕内容", "旧版查询上下文")
        val h = history(messages)
        assertEquals(8, h.length())
        assertTrue(h.getJSONObject(0).getString("text").contains("消息6"))
        assertTrue(h.getJSONObject(7).getString("text").contains("旧版查询上下文"))
        (0 until h.length()).forEach { assertTrue(h.getJSONObject(it).getString("text").length <= 1500) }
    }
    @Test fun earlyGoalsRemainUnverifiedAndOldTransactionsDoNotBecomeNewEntries() {
        val goal = ChatMessage("goal", true, "我希望每月预算2000元")
        val messages = listOf(goal, goal.copy(id = "duplicate"), ChatMessage("spent", true, "昨天午饭36元")) +
            (0..40).map { ChatMessage("other$it", false, "收到") }
        val m = memory(messages)
        assertEquals(1, m.length())
        assertTrue(m.getString(0).contains("每月预算2000元"))
        assertTrue(m.getString(0).contains("未验证"))
        assertTrue(m.getString(0).contains("不是新增交易"))
        assertTrue(m.getString(0).contains("goal"))
        assertFalse(m.toString().contains("昨天午饭"))
    }
    @Test fun memoryIsBoundedAndOnlyUsesThePassedAccountMessages() {
        val messages = (0..30).map { ChatMessage("a$it", true, "我的目标$it" + "字".repeat(1000)) }
        val a = memory(messages)
        assertTrue(a.length() in 1..8)
        (0 until a.length()).forEach { assertTrue(a.getString(it).length <= 500) }
        assertEquals(0, memory(emptyList()).length())
        assertFalse(memory(listOf(ChatMessage("b", true, "我偏好稳妥"))).toString().contains("我的目标"))
    }
    @Test fun savedQueriesCarryForwardExactFiltersWithoutReplayingTransactions() {
        val query = ChatQuery("2026-09-01", "2026-10-01", "老婆", "人情", "", "礼金", "INCOME", true, "张三")
        val context = ConversationMemory.context("已分析", listOf(query))
        val message = ChatMessage("query-source", false, "已分析", context)
        val h = history(listOf(message)).getJSONObject(0).getString("text")
        val m = memory(listOf(message)).getString(0)
        listOf("2026-09-01", "2026-10-01", "老婆", "人情", "INCOME", "true", "张三").forEach {
            assertTrue(h.contains(it)); assertTrue(m.contains(it))
        }
        assertTrue(m.contains("不是新增交易"))
        assertTrue(m.contains("query-source"))
    }
    @Test fun largestValidQueryKeepsItsFullFiltersWithinMemoryLimit() {
        val q = ChatQuery("2026-09-01", "2026-10-01", "甲".repeat(100), "乙".repeat(100), "丙".repeat(100),
            "查询", "EXPENSE", true, "丁".repeat(100))
        val message = ChatMessage("12345678-1234-1234-1234-123456789abc", false, "已查询", ConversationMemory.context("已查询", listOf(q)))
        val item = memory(listOf(message)).getString(0)
        assertTrue(item.length <= 500)
        listOf(q.member, q.category, q.keyword, q.counterparty).forEach { assertTrue(item.contains(it)) }
    }
}
