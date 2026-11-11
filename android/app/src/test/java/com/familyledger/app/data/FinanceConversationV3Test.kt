package com.familyledger.app.data

import com.familyledger.app.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FinanceConversationV3Test {
    private val today = LocalDate.of(2026, 11, 11)

    @Test fun plannerReceivesParentChildPairsIncludingFamilyCustomCategories(): Unit = runBlocking {
        val row = LedgerEntry("custom", EntryType.EXPENSE, "2026-11-01", 500L, "家用机器人",
            categoryL2 = "耗材", account = "现金", member = "老公", recordedBy = "老婆")
        FinanceConversation.respond("今天买耗材5元", today, "老公", emptyList(), listOf(row), "刚刚",
            ai = { operation, input ->
                assertEquals("chat", operation)
                assertTrue(input.getBoolean("queryV3"))
                val tree = input.getJSONArray("categoryTree")
                val custom = (0 until tree.length()).map(tree::getJSONObject).single { it.getString("category") == "家用机器人" }
                assertEquals("EXPENSE", custom.getString("type"))
                assertEquals("耗材", custom.getJSONArray("subcategories").getString(0))
                """{"reply":"请核对提案","entries":[],"query":null,"queries":[]}"""
            })
    }

    @Test fun reportAnalysisKeepsConfirmedQueryWhenExplanationFails() = runBlocking {
        val row = LedgerEntry("one", EntryType.EXPENSE, "2026-11-01", 12345L, "食品酒水",
            account = "现金", member = "老公", recordedBy = "老婆")
        val query = ChatQuery("2026-11-01", "2026-11-12", "老公", "", "", label = "本月老公支出", type = "EXPENSE")
        val answer = FinanceConversation.analyzeQueries("分析我的开支", today, emptyList(), listOf(row), "刚刚", listOf(query),
            ai = { _, _ -> error("temporarily unavailable") })
        assertEquals(listOf(query), answer.queries)
        assertTrue(answer.entries.isEmpty())
        assertTrue(answer.reply.contains("123.45"))
    }

    @Test fun explicitCarQuestionStillReturnsExactAmountWhenModelIsUnavailable() = runBlocking {
        fun row(id: String, cents: Long, child: String, member: String = "老公") =
            LedgerEntry(id, EntryType.EXPENSE, "2026-11-01", cents, "行车交通", categoryL2 = child,
                account = "现金", member = member, recordedBy = "老婆")
        val entries = listOf(row("oil", 20000, "加油"), row("park", 3000, "停车"),
            row("bus", 600, "地铁"), row("other", 5000, "加油", "老婆"), row("ambiguous", 8000, "交通费"))
        val calls = mutableListOf<String>()
        val result = FinanceConversation.respond("我今年在养车上花了多少钱？注意是养车的钱不包括公共交通费用", today,
            "老公", emptyList(), entries, "刚刚", ai = { operation, _ -> calls += operation; error("unavailable") })
        assertEquals(listOf("analyze"), calls)
        assertTrue(result.reply.contains("230.00"))
        assertTrue(result.reply.contains("1 笔"))
        assertTrue(result.entries.isEmpty())
        assertEquals("private_car", result.queries.single().topic)
    }
}
