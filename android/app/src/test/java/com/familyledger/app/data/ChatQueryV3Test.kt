package com.familyledger.app.data

import com.familyledger.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ChatQueryV3Test {
    private fun json() = JSONObject().put("start", "2026-01-01").put("end", "2026-11-12").put("member", "老婆")
        .put("category", "").put("keyword", "").put("label", "今年养车支出").put("type", "EXPENSE")
        .put("giftOnly", false).put("counterparty", "").put("categories", JSONArray()).put("keywords", JSONArray())
        .put("excludeCategories", JSONArray()).put("excludeKeywords", JSONArray()).put("topic", "private_car")
    private fun query(value: JSONObject = json()) = ChatCodec.decode(JSONObject().put("reply", "查询")
        .put("entries", JSONArray()).put("query", JSONObject.NULL).put("queries", JSONArray().put(value)).toString(), "老婆").queries.single()
    private fun row(id: String, category: String, subcategory: String = "", note: String = "", amount: Long = 10) =
        LedgerEntry(id, EntryType.EXPENSE, "2026-11-11", amount, category, subcategory, account = "秘密账户", member = "老婆", recordedBy = "老公", note = note)

    @Test fun privateCarMatchesLegacySubcategoriesAndEvidenceButExcludesTransitPurchaseAndUnknownTransport() {
        val rows = listOf(row("fuel", "行车交通", "加油"), row("tire", "行车交通", "轮胎"), row("insurance", "行车交通", "保险"),
            row("repair", "居家物业", note = "汽车维修"), row("new", "汽车", "保养"), row("bus", "行车交通", "公交"),
            row("taxi", "汽车", note = "滴滴打车"), row("purchase", "汽车", "购车"), row("loan", "汽车", "车贷"),
            row("unknown", "行车交通", "交通费"), row("phone", "数码", "充电"), row("bike", "行车交通", "自行车维修"))
        assertEquals(listOf("fuel", "tire", "insurance", "repair", "new"), ChatCodec.select(query(), rows).map { it.id })
    }
    @Test fun multiCategoryKeywordAndExplicitExclusionsAreDeterministicAndLegacyDimensionsRemainAnd() {
        val q = query(json().put("topic", "").put("categories", JSONArray(listOf("汽车", "行车交通")))
            .put("keywords", JSONArray(listOf("保养", "加油"))).put("excludeKeywords", JSONArray(listOf("公交"))))
        val rows = listOf(row("a", "汽车", "保养"), row("b", "行车交通", "加油"), row("c", "汽车", "保险"),
            row("d", "汽车", "保养", "公交"), row("e", "食品酒水", "保养"))
        assertEquals(listOf("a", "b"), ChatCodec.select(q, rows).map { it.id })
        val legacyGroup = query(json().put("topic", "").put("category", "汽车").put("keywords", JSONArray(listOf("保养", "加油")))
            .put("excludeKeywords", JSONArray(listOf("公交"))))
        assertEquals(listOf("a"), ChatCodec.select(legacyGroup, rows).map { it.id })
    }
    @Test fun exactFactsRespectMemberDateCurrencyDirectionAndRetainPendingClassification() {
        val rows = listOf(row("a", "行车交通", "加油", amount = 10), row("b", "汽车", "保养", amount = 20),
            row("pending", "行车交通", "交通费", "秘密备注", 999), row("other", "汽车").copy(member = "老公"),
            row("future", "汽车").copy(occurredOn = "2026-11-12"), row("deleted", "汽车").copy(deletedAt = 1),
            row("usd", "汽车").copy(currency = "USD"), row("income", "汽车").copy(type = EntryType.INCOME),
            row("adjust", "汽车").copy(type = EntryType.BALANCE_ADJUSTMENT))
        val context = FinanceContext.buildQueries(rows, LocalDate.of(2026, 11, 11), "未同步", listOf(query()))
        val a = context.getJSONArray("facts")
        val facts = (0 until a.length()).associate { a.getJSONObject(it).let { f -> f.getString("label") to f.getString("value") } }
        assertEquals("¥ 0.30", facts["Q1 · 支出"])
        assertEquals("1 笔", facts["Q1 · 待确认分类笔数"])
        assertTrue(facts.values.any { "未计入" in it })
        assertFalse(context.toString().contains("秘密备注"))
    }
    @Test fun v3MemoryPreservesEveryFilterForFollowup() {
        val q = query(json().put("categories", JSONArray(listOf("汽车", "行车交通"))).put("keywords", JSONArray(listOf("保养", "轮胎")))
            .put("excludeCategories", JSONArray(listOf("公共交通"))).put("excludeKeywords", JSONArray(listOf("出租车", "网约车"))))
        val message = ChatMessage("q", false, "已统计", ConversationMemory.context("已统计", listOf(q)))
        val history = ConversationMemory.history(listOf(message)).toString()
        listOf("汽车", "行车交通", "保养", "轮胎", "公共交通", "出租车", "网约车", "private_car").forEach { assertTrue(history.contains(it)) }
    }
    @Test fun invalidOptionalFiltersNeverBroadenAQuery() {
        listOf(json().put("topic", "sql"), json().put("keywords", JSONArray().put(42)),
            json().put("excludeKeywords", "公交"), json().put("categories", JSONArray(List(17) { "a" }))).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { query(value) }
        }
    }
    @Test fun currentYearPlannerUsesOwnerThroughTodayAndNeverParsesATransactionOrUnknownMember() {
        val today = LocalDate.of(2026, 11, 11)
        val q = ChatCodec.privateCarQuery("我今年在养车上花了多少钱？注意是养车的钱不包括公共交通费用", today, "老婆")!!
        assertEquals("2026-01-01", q.start)
        assertEquals("2026-11-12", q.end)
        assertEquals("老婆", q.member)
        assertEquals("private_car", q.topic)
        listOf("今天加油200元", "小玲今年养车花了多少钱", "今年孩子养车花了多少钱", "去年同期养车花了多少钱", "去年和今年养车分别多少钱").forEach {
            assertNull(it, ChatCodec.privateCarQuery(it, today, "老婆"))
        }
    }
    @Test fun narrowFollowupsRetainAllFiltersAndSavedQueryIsAvailableForDetails() {
        val q = query(json().put("excludeKeywords", JSONArray(listOf("出租车"))))
        val message = ChatMessage("q", false, "已统计", ConversationMemory.context("已统计", listOf(q)))
        assertEquals(listOf(q), ConversationMemory.queries(message))
        val next = ChatCodec.privateCarQuery("那去年呢？", LocalDate.of(2026, 11, 11), "老公", listOf(message))!!
        assertEquals("2025-01-01", next.start)
        assertEquals("2026-01-01", next.end)
        assertEquals("老婆", next.member)
        assertEquals(listOf("出租车"), next.excludeKeywords)
        assertEquals("老婆", ChatCodec.privateCarQuery("看看明细", LocalDate.of(2026, 11, 11), "老公", listOf(message))!!.member)
    }
    @Test fun advancedFallbackAnswerIsConciseAndDetailsAreExplicit() {
        val rows = listOf(row("a", "汽车", "保养", amount = 10), row("b", "汽车", "车险", amount = 20), row("c", "行车交通", "交通费"))
        val answer = ChatCodec.answer(query(), rows)
        assertTrue(answer.contains("0.30"))
        assertTrue(answer.contains("1 笔交通费"))
        assertFalse(answer.contains("车险 ¥"))
        assertTrue(answer.length < 250)
        assertTrue(ChatCodec.answer(query().copy(label = "养车明细"), rows).contains("车险 ¥ 0.20"))
    }
    @Test fun deterministicPlannerRefusesUnknownExtraRestrictionsAndDoesNotJumpPastLatestQuery() {
        val today = LocalDate.of(2026, 11, 11)
        listOf("我今年养车花了多少钱不包括保养", "我今年养车花了多少钱只算加油", "明天养车预计花多少钱",
            "2025-01-01到2025-03-01养车多少钱", "养车上小玲花了多少钱", "我今年养车包括购车花多少钱",
            "我今年养车油费花了多少", "我今年养车花了多少，不包括停车费").forEach {
            assertNull(it, ChatCodec.privateCarQuery(it, today, "老婆"))
        }
        val car = query(json().put("excludeKeywords", JSONArray(listOf("洗车"))))
        val savedCar = ChatMessage("car", false, "已统计", ConversationMemory.context("已统计", listOf(car)))
        val latest = ChatQuery("2026-10-01", "2026-11-01", "老公", "食品酒水", "", type = "EXPENSE")
        val savedFood = ChatMessage("food", false, "已统计", ConversationMemory.context("已统计", listOf(latest)))
        assertNull(ChatCodec.privateCarQuery("那去年呢？", today, "老婆", listOf(savedCar, savedFood)))
        val details = ChatCodec.privateCarQuery("看看养车明细", today, "老公", listOf(savedCar))!!
        assertEquals(car.start, details.start)
        assertEquals(car.end, details.end)
        assertEquals(car.member, details.member)
        assertEquals(car.excludeKeywords, details.excludeKeywords)
    }
    @Test fun acquisitionCostsAndPublicCoachDoNotBecomeRunningCostsThroughBroadCarCategory() {
        val rows = listOf(row("a", "汽车", "首付"), row("b", "汽车", "贷款还款"), row("c", "行车交通", "汽车票"),
            row("d", "汽车", "年检"), row("e", "汽车", "过路费", "长途大巴"))
        assertEquals(listOf("d"), ChatCodec.select(query(), rows).map { it.id })
        assertEquals(listOf("unknown"), ChatCodec.uncertain(query(), listOf(row("unknown", "行车交通", "交通费"))).map { it.id })
    }
    @Test fun fuelStationOrAccountNameAloneDoesNotTurnGroceriesIntoCarSpending() {
        val rows = listOf(row("water", "食品酒水", "饮用水").copy(merchant = "高速加油站便利店"),
            row("phone", "行车交通", "充电", "手机充电宝"), row("account", "食品酒水", "午餐").copy(account = "汽车加油专用卡"),
            row("fuel", "行车交通", "加油").copy(account = "汽车贷款银行卡"), row("tire", "其他支出", note = "更换汽车轮胎"))
        assertEquals(listOf("fuel", "tire"), ChatCodec.select(query(), rows).map { it.id })
    }
    @Test fun categoryLevelPreventsLegacyFirstLevelNamesFromMatchingAnotherSecondLevel() {
        val rows = listOf(row("legacy", "工资").copy(type = EntryType.INCOME), row("new", "职业收入", "工资").copy(type = EntryType.INCOME))
        val root = json().put("topic", "").put("type", "INCOME").put("category", "工资")
        assertEquals(listOf("legacy"), ChatCodec.select(query(JSONObject(root.toString()).put("categoryLevel", "L1")), rows).map { it.id })
        assertEquals(listOf("new"), ChatCodec.select(query(JSONObject(root.toString()).put("categoryLevel", "L2")), rows).map { it.id })
        assertEquals(listOf("legacy", "new"), ChatCodec.select(query(root), rows).map { it.id })
        assertThrows(IllegalArgumentException::class.java) { query(json().put("categoryLevel", "L3")) }
    }
    @Test fun semanticCarFiltersUseCanonicalAliasesWithoutChangingStoredOrStrictReportCategories() {
        val rows = listOf(row("old-fuel", "行车交通", "加油", amount = 10), row("old-parking", "行车交通", "停车", amount = 20),
            row("new-fuel", "私家车", "油费", amount = 30), row("uncertain", "行车交通", "交通费", amount = 40))
        val oil = query(json().put("keywords", JSONArray(listOf("油费"))))
        assertEquals(listOf("old-fuel", "new-fuel"), ChatCodec.select(oil, rows).map { it.id })
        assertTrue(ChatCodec.answer(oil, rows).contains("¥ 0.40"))
        val withoutParking = query(json().put("excludeKeywords", JSONArray(listOf("停车费"))))
        assertEquals(listOf("old-fuel", "new-fuel"), ChatCodec.select(withoutParking, rows).map { it.id })
        val category = query(json().put("topic", "").put("category", "私家车"))
        assertEquals(listOf("old-fuel", "old-parking", "new-fuel"), ChatCodec.select(category, rows).map { it.id })
        val exactL1 = query(json().put("topic", "").put("category", "私家车").put("categoryLevel", "L1"))
        assertEquals(listOf("new-fuel"), ChatCodec.select(exactL1, rows).map { it.id })
        val exactL2 = query(json().put("topic", "").put("category", "油费").put("categoryLevel", "L2"))
        assertEquals(listOf("new-fuel"), ChatCodec.select(exactL2, rows).map { it.id })
        val rawKeyword = query(json().put("categoryLevel", "L1").put("keywords", JSONArray(listOf("油费"))))
        assertEquals(listOf("new-fuel"), ChatCodec.select(rawKeyword, rows).map { it.id })
        assertEquals("行车交通", rows.first().categoryL1)
        assertEquals("加油", rows.first().categoryL2)
    }
}
