package com.familyledger.app.domain

import org.junit.Assert.*
import org.junit.Test

class CategoryCatalogTest {
    private fun row(parent: String = "行车交通", child: String = "", note: String = "") = LedgerEntry(
        "test-id", EntryType.EXPENSE, "2026-11-11", 12345L, parent, child,
        "现金", "家人", "操作者", merchant = "测试商家", project = "测试项目", note = note,
        updatedAt = 123L, origin = ImportOrigin("a".repeat(64), "合成表.xlsx", "支出", 2, 10L,
            "2026/11/11", rawFields = mapOf("分类" to parent)), isGift = true, counterparty = "对象甲")

    @Test fun choicesKeepStableTransportHierarchyAndTypeBoundaries() {
        val expense = CategoryCatalog.choices(EntryType.EXPENSE, emptyList())
        assertEquals(listOf("油费", "充电费", "停车费", "通行费", "车辆保险", "维修保养"), expense["私家车"])
        assertEquals(listOf("公交地铁", "出租网约车", "铁路航空"), expense["公共出行"])
        assertFalse(expense.containsKey("油费"))
        assertFalse(expense.containsKey("职业收入"))
        assertTrue(CategoryCatalog.choices(EntryType.INCOME, emptyList())["职业收入"]!!.contains("工资"))
        assertFalse(CategoryCatalog.choices(EntryType.INCOME, emptyList()).containsKey("私家车"))
    }

    @Test fun choicesAppendSavedCustomPairsWithoutMovingOldRows() {
        val custom = row("家庭专用", "宠物寄养")
        val standardCustom = row("食品酒水", "周末烘焙")
        val rows = listOf(custom, standardCustom, custom.copy(id = "dup"),
            row("收入专用", "稿费").copy(type = EntryType.INCOME), row("已删除", "子类").copy(deletedAt = 1))
        val choices = CategoryCatalog.choices(EntryType.EXPENSE, rows)
        assertEquals(listOf("宠物寄养"), choices["家庭专用"])
        assertTrue(choices["食品酒水"]!!.contains("周末烘焙"))
        assertFalse(choices.containsKey("收入专用"))
        assertFalse(choices.containsKey("已删除"))
        assertEquals("家庭专用", custom.categoryL1)
    }

    @Test fun aiChoicesExcludeOldTransportLabelsWhileEditorChoicesKeepThem() {
        val rows = listOf(row(child = "加油"), row(child = "交通费"), row("充电费"), row("家庭专用", "专项"))
        val aiChoices = CategoryCatalog.canonicalChoices(EntryType.EXPENSE, rows)
        assertFalse(aiChoices.containsKey("行车交通"))
        assertFalse(aiChoices.containsKey("充电费"))
        assertEquals(listOf("专项"), aiChoices["家庭专用"])
        assertTrue(aiChoices["私家车"]!!.contains("油费"))
        assertTrue(CategoryCatalog.choices(EntryType.EXPENSE, rows).containsKey("行车交通"))
        assertEquals("行车交通", rows.first().categoryL1)
    }

    @Test fun clearLegacyTransportSubcategoriesBecomePreviewSuggestions() {
        listOf(
            "加油" to ("私家车" to "油费"), "充电费" to ("私家车" to "充电费"),
            "停车费" to ("私家车" to "停车费"), "过路费" to ("私家车" to "通行费"),
            "车险" to ("私家车" to "车辆保险"), "车辆保养" to ("私家车" to "维修保养"),
            "地铁" to ("公共出行" to "公交地铁"), "打车" to ("公共出行" to "出租网约车"),
            "高铁票" to ("公共出行" to "铁路航空")
        ).forEach { (old, expected) ->
            val suggestion = CategoryCatalog.suggested(row(child = old))!!
            assertEquals(old, expected, suggestion.categoryL1 to suggestion.categoryL2)
        }
    }

    @Test fun genericTransportWithoutEvidenceIsNeverGuessed() {
        listOf(row(child = "交通费"), row(parent = "交通费"), row(child = "其他交通"),
            row(child = "交通费", note = "上班途中花了36元")).forEach { assertNull(CategoryCatalog.suggested(it)) }
    }

    @Test fun genericOtherTransportWithoutNotesRemainsAmbiguous() {
        val original = row(child = "其他")
        assertNull(CategoryCatalog.suggested(original))
        assertEquals(original, CategoryCatalog.normalize(original))
    }

    @Test fun genericOtherTransportUsesExplicitFuelNotesAndKeepsOtherFields() {
        val original = row(child = "其他", note = "汽车加油123.45元")
        val expected = original.copy(categoryL1 = "私家车", categoryL2 = "油费")
        assertEquals(expected, CategoryCatalog.suggested(original))
        assertEquals(expected, CategoryCatalog.normalize(original))
        assertEquals("家人", original.member)
        assertEquals("操作者", original.recordedBy)
        assertEquals("汽车加油123.45元", original.note)
    }

    @Test fun notesOnlySuggestWhenEvidenceIsClearAndUnambiguous() {
        assertEquals("私家车" to "充电费", CategoryCatalog.suggested(row(note = "汽车充电28元"))!!.let { it.categoryL1 to it.categoryL2 })
        assertEquals("公共出行" to "公交地铁", CategoryCatalog.suggested(row(child = "交通费", note = "地铁6元"))!!.let { it.categoryL1 to it.categoryL2 })
        assertNull(CategoryCatalog.suggested(row(note = "停车后充电共28元")))
    }

    @Test fun electronicChargingMerchantNamesAndGenericInsuranceDoNotBecomeCarCosts() {
        listOf(
            row(note = "手机充电6元"), row(note = "共享充电宝6元"), row(note = "购买充电器25元"),
            row(note = "平板充电配件"), row(note = "买了保险300元"),
            row(note = "在加油站买矿泉水5元"), row().copy(merchant = "加油便利店")
        ).forEach { assertNull(it.note.ifBlank { it.merchant }, CategoryCatalog.suggested(it)) }
    }

    @Test fun normalizationRepairsWrongLevelAndWrongKnownParent() {
        val primary = CategoryCatalog.normalize(row("充电费"))
        assertEquals("私家车" to "充电费", primary.categoryL1 to primary.categoryL2)
        val wrongParent = CategoryCatalog.normalize(row("公共出行", "停车费"))
        assertEquals("私家车" to "停车费", wrongParent.categoryL1 to wrongParent.categoryL2)
        val income = CategoryCatalog.normalize(row("工资").copy(type = EntryType.INCOME))
        assertEquals("职业收入" to "工资", income.categoryL1 to income.categoryL2)
    }

    @Test fun classificationChangesOnlyTheCategoryPair() {
        val original = row(child = "加油", note = "加油123.45元")
        val normalized = CategoryCatalog.normalize(original)
        assertEquals(original.copy(categoryL1 = "私家车", categoryL2 = "油费"), normalized)
        assertEquals(12345L, normalized.amountMinor)
        assertEquals("行车交通", original.categoryL1)
        assertEquals("加油", original.categoryL2)
    }

    @Test fun validAndCustomExplicitCategoriesArePreservedDespiteNotes() {
        listOf(row("公共出行", "出租网约车", "停车费"), row("家庭自定义", "专项", "汽车充电"),
            row("食品酒水", "周末烘焙", "咖啡与加油"), row("家庭自定义", "", "地铁6元")).forEach {
            assertEquals(it, CategoryCatalog.normalize(it))
            assertNull(CategoryCatalog.suggested(it))
        }
    }

    @Test fun historyPreviewSkipsDeletedForeignCurrencyAndBalanceRows() {
        listOf(row(child = "加油").copy(deletedAt = 1), row(child = "加油").copy(currency = "USD"),
            row(child = "加油").copy(type = EntryType.BALANCE_ADJUSTMENT)).forEach {
            assertNull(CategoryCatalog.suggested(it))
            assertEquals(it, CategoryCatalog.normalize(it))
        }
    }

    @Test fun repeatedExactMerchantCategoryIsOnlyAppliedToUnclassifiedDrafts() {
        val past = row("家庭专用", "宠物寄养").copy(id = "past1")
        val history = listOf(past, past.copy(id = "past2"))
        val draft = row("其他支出")
        assertEquals(draft.copy(categoryL1 = "家庭专用", categoryL2 = "宠物寄养"), CategoryCatalog.normalize(draft, history))
        val explicit = row("食品酒水", "午餐")
        assertEquals(explicit, CategoryCatalog.normalize(explicit, history))
        assertNull(CategoryCatalog.suggested(draft))
        assertEquals("家庭专用", past.categoryL1)
    }

    @Test fun merchantMemoryNeedsTwoIndependentMatchingRowsAndNoConflict() {
        val draft = row("其他支出")
        val past = row("家庭专用", "宠物寄养").copy(id = "past1")
        val invalidHistories = listOf(
            listOf(past), listOf(past, past),
            listOf(past, past.copy(id = "past2", categoryL2 = "其它")),
            listOf(past, past.copy(id = "past2", type = EntryType.INCOME)),
            listOf(past, past.copy(id = "past2", deletedAt = 1)),
            listOf(past, past.copy(id = "past2", merchant = "测试商家分店"))
        )
        invalidHistories.forEach { assertEquals(draft, CategoryCatalog.normalize(draft, it)) }
        val noMerchant = draft.copy(merchant = "")
        assertEquals(noMerchant, CategoryCatalog.normalize(noMerchant, listOf(past.copy(merchant = ""), past.copy(id = "2", merchant = ""))))
    }
}
