package com.familyledger.app.domain

/** Family defaults plus saved custom pairs. Applying a suggestion is always a caller decision. */
object CategoryCatalog {
    private val expense = linkedMapOf(
        "食品酒水" to listOf("早餐", "午餐", "晚餐", "买菜", "水果", "零食饮品", "聚餐"),
        "衣服饰品" to listOf("衣服裤子", "鞋帽配饰"),
        "居家物业" to listOf("房租", "房贷", "水费", "电费", "燃气", "物业费", "日用百货", "家政维修"),
        "私家车" to listOf("油费", "充电费", "停车费", "通行费", "车辆保险", "维修保养"),
        "公共出行" to listOf("公交地铁", "出租网约车", "铁路航空"),
        "通讯网络" to listOf("手机话费", "宽带", "数码设备"),
        "休闲娱乐" to listOf("旅游", "电影", "游戏", "运动健身", "娱乐休闲"),
        "学习教育" to listOf("学费", "书籍文具", "培训", "教育用品"),
        "医疗保健" to listOf("药品", "门诊", "住院", "体检", "医疗保险"),
        "人情往来" to listOf("随礼", "红包", "孝敬长辈", "请客送礼"),
        "金融保险" to listOf("手续费", "利息支出", "保险"),
        "其他支出" to listOf("其他")
    )
    private val income = linkedMapOf(
        "职业收入" to listOf("工资", "奖金", "兼职", "经营收入"),
        "投资收入" to listOf("利息", "分红", "投资收益"),
        "人情收入" to listOf("礼金", "红包"),
        "其他收入" to listOf("报销", "补贴", "中奖", "其他")
    )
    private val legacyTransport = setOf("行车交通", "交通", "交通费", "交通出行", "出行交通", "汽车交通")
    private val genericChildren = setOf("", "其他", "其它", "交通费", "其他交通", "其它交通")

    private data class Rule(val parent: String, val child: String, val words: List<String>) {
        val pair get() = parent to child
    }
    private fun rule(parent: String, child: String, vararg words: String) = Rule(parent, child, words.toList())
    private val expenseRules = listOf(
        rule("私家车", "油费", "加油", "加油费", "汽油", "汽油费", "柴油", "燃油费"),
        rule("私家车", "充电费", "充电", "汽车充电", "车辆充电"),
        rule("私家车", "停车费", "停车"),
        rule("私家车", "通行费", "过路费", "过桥费", "高速费", "高速通行费", "路桥费"),
        rule("私家车", "车辆保险", "车险", "交强险", "商业车险", "汽车保险"),
        rule("私家车", "维修保养", "汽车维修", "车辆维修", "车辆保养", "汽车保养", "保养", "修车", "洗车"),
        rule("公共出行", "公交地铁", "公交", "地铁", "公交费", "地铁费"),
        rule("公共出行", "出租网约车", "出租车", "网约车", "打车", "滴滴", "出租车费"),
        rule("公共出行", "铁路航空", "高铁", "火车", "机票", "飞机票", "高铁票", "火车票"),
        rule("食品酒水", "早餐", "早饭"),
        rule("食品酒水", "午餐", "午饭"),
        rule("食品酒水", "晚餐", "晚饭"),
        rule("食品酒水", "买菜"),
        rule("食品酒水", "水果"),
        rule("食品酒水", "零食饮品", "零食", "奶茶", "咖啡", "饮料"),
        rule("医疗保健", "药品", "买药"),
        rule("医疗保健", "门诊", "看病", "挂号"),
        rule("居家物业", "房租"),
        rule("居家物业", "水费"),
        rule("居家物业", "电费"),
        rule("居家物业", "燃气", "燃气费")
    )
    private val incomeRules = listOf(
        rule("职业收入", "工资", "工资到账", "薪资", "薪水"),
        rule("职业收入", "奖金", "年终奖"),
        rule("职业收入", "兼职"),
        rule("职业收入", "经营收入"),
        rule("投资收入", "利息"),
        rule("投资收入", "分红")
    )

    private fun defaults(type: EntryType): Map<String, List<String>> = when (type) {
        EntryType.EXPENSE -> expense
        EntryType.INCOME -> income
        EntryType.BALANCE_ADJUSTMENT -> linkedMapOf("余额调整" to emptyList())
    }

    fun choices(type: EntryType, entries: List<LedgerEntry>): Map<String, List<String>> {
        val result = linkedMapOf<String, LinkedHashSet<String>>()
        defaults(type).forEach { (parent, children) -> result[parent] = LinkedHashSet(children) }
        entries.asSequence().filter { it.type == type && it.deletedAt == null && it.currency == "CNY" }
            .map { it.categoryL1.trim() to it.categoryL2.trim() }.filter { it.first.isNotBlank() }
            .distinct().sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .forEach { (parent, child) ->
                val children = result.getOrPut(parent) { linkedSetOf() }
                if (child.isNotBlank()) children.add(child)
            }
        return result.mapValues { it.value.toList() }
    }

    /** For AI context: legacy ambiguous transport labels remain editable but are not new defaults. */
    fun canonicalChoices(type: EntryType, entries: List<LedgerEntry>): Map<String, List<String>> =
        choices(type, entries.map { suggested(it) ?: it }.filter { it.categoryL1.trim() !in legacyTransport })

    /** Only for new, unconfirmed AI drafts; never run this over stored entries during loading. */
    fun normalize(entry: LedgerEntry, history: List<LedgerEntry> = emptyList()): LedgerEntry {
        if (!eligible(entry)) return entry
        suggested(entry)?.let { return it }
        if (!genericDraft(entry)) return entry
        val merchant = entry.merchant.trim()
        if (merchant.isBlank()) return entry
        val matches = history.filter { eligible(it) && it.id != entry.id && it.type == entry.type &&
            it.merchant.trim() == merchant && it.categoryL1.isNotBlank() }.distinctBy { it.id }
        if (matches.size < 2) return entry
        val pairs = matches.map { previous ->
            classification(previous) ?: (previous.categoryL1.trim() to previous.categoryL2.trim())
        }.distinct()
        return if (pairs.size == 1) entry.withCategory(pairs.single()) else entry
    }

    /** Conservative preview only. Unknown custom labels and ambiguous transport stay untouched. */
    fun suggested(entry: LedgerEntry): LedgerEntry? {
        if (!eligible(entry)) return null
        val pair = classification(entry) ?: return null
        return entry.withCategory(pair).takeIf { it.categoryL1 != entry.categoryL1 || it.categoryL2 != entry.categoryL2 }
    }

    private fun eligible(entry: LedgerEntry) = entry.type != EntryType.BALANCE_ADJUSTMENT &&
        entry.deletedAt == null && entry.currency == "CNY"

    private fun rules(type: EntryType) = if (type == EntryType.INCOME) incomeRules else expenseRules

    private fun namedPair(type: EntryType, name: String): Pair<String, String>? {
        val named = defaults(type).flatMap { (parent, children) -> children.filter { it == name }.map { parent to it } }
        if (named.size == 1) return named.single()
        return rules(type).filter { name in it.words }.map { it.pair }.distinct().singleOrNull()
    }

    private fun classification(entry: LedgerEntry): Pair<String, String>? {
        val parent = entry.categoryL1.trim()
        val child = entry.categoryL2.trim()
        val template = defaults(entry.type)
        // A complete template pair is explicit, including cases where the note mentions other costs.
        if (child.isNotBlank() && child in template[parent].orEmpty()) return null
        val primaryAsChild = namedPair(entry.type, parent)
        if (parent.isNotBlank() && parent !in template && parent !in legacyTransport && primaryAsChild == null) return null
        if (child !in genericChildren) namedPair(entry.type, child)?.let { return it }
        primaryAsChild?.let { return it }
        // A new custom child within a default parent is also an explicit family choice.
        if (child !in genericChildren) return null
        // A shop name alone describes the merchant, not what was bought there.
        val text = entry.note
        val hints = rules(entry.type).filter { rule ->
            if (rule.child == "充电费") {
                if (listOf("手机", "充电宝", "充电器", "充电线", "平板", "笔记本", "耳机").any { it in text }) return@filter false
                if (parent !in legacyTransport && parent != "私家车" &&
                    listOf("汽车", "车辆", "电动车", "充电桩", "新能源车").none { it in text }) return@filter false
            }
            if (rule.child == "维修保养" && listOf("护肤", "皮肤", "美容").any { it in text }) return@filter false
            val evidence = if (rule.child == "油费") text.replace("加油站", "") else text
            val words = rule.words + rule.child
            words.any { word -> word in evidence && !(word == "电费" && "充电" in text) }
        }.map { it.pair }.distinct()
        return hints.singleOrNull()
    }

    private fun genericDraft(entry: LedgerEntry): Boolean {
        val parent = entry.categoryL1.trim()
        return entry.categoryL2.trim() in genericChildren && (parent.isBlank() ||
            parent == "其他支出" || parent == "其他收入" || parent in legacyTransport)
    }

    private fun LedgerEntry.withCategory(pair: Pair<String, String>) = copy(categoryL1 = pair.first, categoryL2 = pair.second)
}
