package com.familyledger.app.data

import com.familyledger.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class QuickEntryCategoryTest {
    private val today = LocalDate.of(2026, 11, 11)

    @Test fun offlineTransportUsesPrivateAndPublicParentChildPairs() {
        val expected = mapOf(
            "加油320.01元" to ("私家车" to "油费"),
            "充电28.50元" to ("私家车" to "充电费"),
            "地铁6元" to ("公共出行" to "公交地铁"),
            "打车18元" to ("公共出行" to "出租网约车")
        )
        expected.forEach { (text, pair) ->
            val entry = QuickEntryCodec.local(text, today).single()
            assertEquals(text, pair, entry.categoryL1 to entry.categoryL2)
        }
        assertEquals(32001L, QuickEntryCodec.local("加油320.01元", today).single().amountMinor)
    }

    @Test fun offlineFoodAndIncomeReceiveEditableSubcategories() {
        val lunch = QuickEntryCodec.local("我用微信支付午饭36.80元", today).single()
        assertEquals("食品酒水" to "午餐", lunch.categoryL1 to lunch.categoryL2)
        assertEquals(3680L, lunch.amountMinor)
        assertEquals("微信", lunch.account)
        val salary = QuickEntryCodec.local("工资到账5000元", today).single()
        assertEquals(EntryType.INCOME, salary.type)
        assertEquals("职业收入" to "工资", salary.categoryL1 to salary.categoryL2)
    }

    private fun cloud(category: String, child: String = "", note: String = "") = QuickEntryCodec.cloud(
        JSONObject().put("entries", JSONArray().put(JSONObject()
            .put("type", "EXPENSE").put("amount", "123.45").put("date", "2026-11-11")
            .put("category", category).put("subcategory", child).put("account", "现金")
            .put("member", "家人").put("recordedBy", "本人").put("merchant", "测试商家")
            .put("project", "测试项目").put("note", note).put("isGift", true).put("counterparty", "对象甲")))
            .toString()).single()

    @Test fun cloudSecondaryAsPrimaryIsRepairedWithoutChangingOtherDraftFields() {
        val entry = cloud("充电费", note = "汽车充电")
        assertEquals("私家车" to "充电费", entry.categoryL1 to entry.categoryL2)
        assertEquals(12345L, entry.amountMinor)
        assertEquals("现金", entry.account)
        assertEquals("家人", entry.member)
        assertEquals("本人", entry.recordedBy)
        assertEquals("测试商家", entry.merchant)
        assertEquals("测试项目", entry.project)
        assertEquals("汽车充电", entry.note)
        assertTrue(entry.isGift)
        assertEquals("对象甲", entry.counterparty)
    }

    @Test fun cloudCustomCategoriesRemainAvailableToTheFamily() {
        val entry = cloud("家庭自定义", "专项开销", "午饭与充电")
        assertEquals("家庭自定义" to "专项开销", entry.categoryL1 to entry.categoryL2)
        assertEquals(12345L, entry.amountMinor)
    }
}
