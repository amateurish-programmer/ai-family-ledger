package com.familyledger.app.data

import com.familyledger.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class ChatCodecTest {
    private fun response(member: String = "本人", amount: String = "36.80") = """{"reply":"请确认记录","query":null,"entries":[{"type":"EXPENSE","amount":"$amount","date":"2026-09-06","category":"食品酒水","subcategory":"午餐","account":"微信","member":"$member","recordedBy":"错误角色","merchant":"","project":"","note":"午饭"}]}"""
    @Test fun defaultMemberAndRecorderUseLocalRole() {
        val row = ChatCodec.decode(response(), "老公").entries.single()
        assertEquals("老公", row.member); assertEquals("老公", row.recordedBy); assertEquals(3680L, row.amountMinor)
    }
    @Test fun explicitPayerDoesNotChangeRecorder() {
        val row = ChatCodec.decode(response("老婆"), "老公").entries.single()
        assertEquals("老婆", row.member); assertEquals("老公", row.recordedBy)
    }
    @Test fun ordinaryReplyDoesNotCreateRecords() {
        val result = ChatCodec.decode("""{"reply":"可以在设置中编辑本机角色。","entries":[],"query":null}""", "老婆")
        assertTrue(result.entries.isEmpty()); assertNull(result.query)
    }
    @Test fun invalidAmountAndExtraActionAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ChatCodec.decode(response(amount = "0.001"), "老公") }
        assertThrows(IllegalArgumentException::class.java) { ChatCodec.decode("""{"reply":"删除","entries":[],"query":null,"delete":true}""", "老公") }
    }
    @Test fun queryUsesExactMoneyAndExcludesOtherScope() {
        val row = ChatCodec.decode(response("老婆", "0.10"), "老公").entries.single()
        val rows = listOf(row, row.copy(id = "second", amountMinor = 20), row.copy(id = "other", member = "老公", amountMinor = 99999),
            row.copy(id = "deleted", deletedAt = 1), row.copy(id = "future", occurredOn = "2026-10-01"),
            row.copy(id = "adjust", type = EntryType.BALANCE_ADJUSTMENT), row.copy(id = "foreign", currency = "USD"))
        val answer = ChatCodec.answer(ChatQuery("2026-09-01", "2026-10-01", "老婆", "午餐", "午饭"), rows)
        assertTrue(answer.contains("共 2 笔收支")); assertTrue(answer.contains("支出 ¥ 0.30")); assertTrue(answer.contains("结余 ¥ -0.30"))
        val all = ChatCodec.answer(ChatQuery("2026-09-01", "2026-10-01", "", "", ""), rows)
        assertTrue(all.contains("老婆：¥ 0.30")); assertTrue(all.contains("老公：¥ 999.99"))
    }
    @Test fun invalidQueryDateAndRoleAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ChatCodec.decode("""{"reply":"查询","entries":[],"query":{"start":"2026-02-30","end":"2026-03-01","member":"","category":"","keyword":""}}""", "老公") }
        assertThrows(IllegalArgumentException::class.java) { ChatCodec.role(" ") }
        assertThrows(IllegalArgumentException::class.java) { ChatCodec.role("老\n公") }
    }
}
