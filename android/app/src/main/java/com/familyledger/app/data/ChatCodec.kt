package com.familyledger.app.data

import com.familyledger.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class ChatQuery(val start: String, val end: String, val member: String, val category: String, val keyword: String,
    val label: String = "", val type: String = "", val giftOnly: Boolean = false, val counterparty: String = "",
    val categories: List<String> = emptyList(), val keywords: List<String> = emptyList(),
    val excludeCategories: List<String> = emptyList(), val excludeKeywords: List<String> = emptyList(), val topic: String = "",
    val categoryLevel: String = "")
data class ChatResult(val reply: String, val entries: List<LedgerEntry>, val query: ChatQuery?, val queries: List<ChatQuery> = emptyList())
data class ChatMessage(val id: String, val user: Boolean, val text: String, val context: String = text)

object ChatCodec {
    fun role(value: String): String = value.trim().also {
        require(it.length in 1..20 && it.none { c -> c.isISOControl() }) { "角色须为 1 至 20 字，例如老公、老婆" }
    }

    fun localFallback(text: String, today: LocalDate, localRole: String): ChatResult {
        val who = role(localRole)
        val entries = QuickEntryCodec.local(text, today).map { entry ->
            entry.copy(member = who, recordedBy = who)
        }
        return ChatResult("云端暂时不可用，已在本机整理出待确认记录，请核对后保存。", entries, null)
    }

    fun decode(text: String, localRole: String): ChatResult {
        val who = role(localRole)
        val root = JSONObject(text)
        require(root.keys().asSequence().toSet() in listOf(setOf("reply", "entries", "query"), setOf("reply", "entries", "query", "queries"))) { "回复格式无效，请重试" }
        val reply = root.get("reply") as? String ?: error("回复格式无效")
        require(reply.isNotBlank() && reply.length <= 6000)
        val raw = root.getJSONArray("entries")
        val entries = if (raw.length() == 0) emptyList() else QuickEntryCodec.cloud(JSONObject().put("entries", raw).toString()).map {
            it.copy(member = if (it.member.trim() in setOf("本人", "我", "未指定")) who else it.member, recordedBy = who)
        }
        val query = if (root.isNull("query")) null else queryFromJson(root.getJSONObject("query"))
        val queries = if (!root.has("queries")) emptyList() else {
            val array = root.getJSONArray("queries")
            require(array.length() <= 4) { "一次最多查询四组范围" }
            (0 until array.length()).map { queryFromJson(array.getJSONObject(it), allowLegacy = false) }
        }
        require(query == null || queries.isEmpty()) { "查询格式冲突" }
        require(entries.isEmpty() || (query == null && queries.isEmpty())) { "请将记账与账本查询分开发送" }
        return ChatResult(reply, entries, query, queries)
    }

    internal fun validateQuery(query: ChatQuery): ChatQuery {
        require(validateDate(query.start) < validateDate(query.end)) { "查询日期范围无效" }
        listOf(query.member, query.category, query.keyword, query.label, query.type, query.counterparty).forEach {
            require(it.length <= 100 && it.none(Char::isISOControl)) { "查询字段无效" }
        }
        require(query.type in setOf("", "INCOME", "EXPENSE")) { "查询收支类型无效" }
        require(query.topic in setOf("", "private_car")) { "查询主题无效" }
        require(query.categoryLevel in setOf("", "L1", "L2")) { "分类层级无效" }
        listOf(query.categories, query.keywords, query.excludeCategories, query.excludeKeywords).forEach { values ->
            require(values.size <= 16 && values.all { it.isNotBlank() && it.length <= 100 && it.none(Char::isISOControl) }) { "查询筛选列表无效" }
        }
        return query
    }

    internal fun queryFromJson(q: JSONObject, allowLegacy: Boolean = true): ChatQuery {
        val oldKeys = setOf("start", "end", "member", "category", "keyword")
        val currentKeys = oldKeys + setOf("label", "type", "giftOnly", "counterparty")
        val optionalKeys = setOf("categories", "keywords", "excludeCategories", "excludeKeywords", "topic", "categoryLevel")
        val keys = q.keys().asSequence().toSet()
        require((keys.containsAll(currentKeys) && (keys - currentKeys).all { it in optionalKeys }) || (allowLegacy && keys == oldKeys)) { "查询字段无效" }
        fun field(name: String) = q.get(name) as? String ?: throw IllegalArgumentException("查询字段无效")
        fun fields(name: String): List<String> {
            if (!q.has(name)) return emptyList()
            val array = q.opt(name) as? JSONArray ?: throw IllegalArgumentException("查询筛选列表无效")
            require(array.length() <= 16) { "查询筛选列表过长" }
            return (0 until array.length()).map { array.get(it) as? String ?: throw IllegalArgumentException("查询筛选列表无效") }
        }
        val legacy = keys == oldKeys
        return validateQuery(ChatQuery(field("start"), field("end"), field("member"), field("category"), field("keyword"),
            if (legacy) "" else field("label"), if (legacy) "" else field("type"),
            if (legacy) false else q.get("giftOnly") as? Boolean ?: throw IllegalArgumentException("礼金筛选格式无效"),
            if (legacy) "" else field("counterparty"), fields("categories"), fields("keywords"),
            fields("excludeCategories"), fields("excludeKeywords"), if (q.has("topic")) field("topic") else "",
            if (q.has("categoryLevel")) field("categoryLevel") else ""))
    }

    internal fun queryJson(query: ChatQuery): JSONObject = validateQuery(query).let {
        JSONObject().put("start", it.start).put("end", it.end).put("member", it.member).put("category", it.category)
            .put("keyword", it.keyword).put("label", it.label).put("type", it.type).put("giftOnly", it.giftOnly).put("counterparty", it.counterparty)
            .put("categories", JSONArray(it.categories)).put("keywords", JSONArray(it.keywords))
            .put("excludeCategories", JSONArray(it.excludeCategories)).put("excludeKeywords", JSONArray(it.excludeKeywords)).put("topic", it.topic)
            .put("categoryLevel", it.categoryLevel)
    }

    internal fun advanced(query: ChatQuery): Boolean = query.topic.isNotEmpty() || query.categoryLevel.isNotEmpty() ||
        listOf(query.categories, query.keywords, query.excludeCategories, query.excludeKeywords).any { it.isNotEmpty() }

    private fun fields(entry: LedgerEntry) = listOf(entry.note, entry.merchant, entry.project, entry.account, entry.categoryL1, entry.categoryL2)

    private fun baseMatch(query: ChatQuery, e: LedgerEntry): Boolean {
        val categories = query.categories + listOf(query.category).filter(String::isNotBlank)
        val keywords = query.keywords + listOf(query.keyword).filter(String::isNotBlank)
        // Free-form queries understand old category names through the same
        // conservative suggestions shown in the editor. Explicit report levels
        // keep their saved category identity; neither path rewrites the row.
        val canonical = if (query.categoryLevel.isEmpty()) CategoryCatalog.suggested(e) else null
        val aliases = listOfNotNull(canonical?.categoryL1, canonical?.categoryL2)
        val text = fields(e) + aliases
        fun categoryMatches(value: String) = when (query.categoryLevel) {
            "L1" -> value == e.categoryL1
            "L2" -> value == e.categoryL2
            else -> value == e.categoryL1 || value == e.categoryL2 || value in aliases
        }
        return e.deletedAt == null && e.currency == "CNY" && e.type != EntryType.BALANCE_ADJUSTMENT &&
            e.occurredOn >= query.start && e.occurredOn < query.end &&
            (query.member.isBlank() || e.member == query.member) &&
            (categories.isEmpty() || categories.any(::categoryMatches)) &&
            (query.type.isBlank() || e.type.name == query.type) &&
            (!query.giftOnly || e.isGift) &&
            (query.counterparty.isBlank() || (e.isGift && e.counterparty == query.counterparty)) &&
            (keywords.isEmpty() || keywords.any { keyword -> text.any { keyword in it } }) &&
            query.excludeCategories.none(::categoryMatches) &&
            query.excludeKeywords.none { keyword -> text.any { keyword in it } }
    }

    // Legacy 行车交通 combines car use and public transport. A generic transport
    // label is not enough evidence to count it as private-car running cost.
    private val nonCar = Regex("公共交通|公交|地铁|巴士|大巴|中巴|汽车票|长途汽车|出租车|出租汽车|打车|网约车|滴滴|高铁|铁路|火车|动车|客运|机票|飞机|轮渡|船票|共享单车|共享电|自行车|电瓶车|电动两轮|摩托车|购车|买车|买新车|车款|汽车购置|车辆购置|购置税|车贷|汽车贷款|车辆贷款|汽车分期")
    private val carEvidence = Regex("油费|加油|汽油|柴油|燃油|轮胎|洗车|车险|交强险|车船税|汽车保险|车辆保险|汽车维修|车辆维修|汽车保养|车辆保养|汽车配件|汽车用品|停车|路桥|过路|通行费|高速|ETC|汽车充电|车辆充电|新能源车充电|充电桩", RegexOption.IGNORE_CASE)
    private val carCategory = Regex("私家车|养车|用车|车辆养护|汽车|自驾")
    private val transportCategory = Regex("行车交通|交通|车辆|车费")
    private fun carMatch(entry: LedgerEntry): Int {
        if (entry.type != EntryType.EXPENSE) return 0
        // Accounts and merchants are too weak for positive evidence (a drink
        // bought at a fuel station is still a drink). Explicit categories/notes
        // supply the transaction meaning, while payment methods never do.
        val text = listOf(entry.categoryL1, entry.categoryL2, entry.note).joinToString("；")
        if (nonCar.containsMatchIn(text)) return 0
        val category = "${entry.categoryL1}；${entry.categoryL2}"
        if (carCategory.containsMatchIn(category) && Regex("首付|尾款|分期|贷款|还款|还贷").containsMatchIn(text)) return 0
        if (Regex("手机|充电宝|充电器|充电线|平板|笔记本|耳机").containsMatchIn(text) && "充电" in text) return 0
        if (carEvidence.containsMatchIn(text) || carCategory.containsMatchIn(category)) return 1
        if (transportCategory.containsMatchIn(entry.categoryL1) && Regex("保险|维修|保养|充电|年检|检车").containsMatchIn(entry.categoryL2)) return 1
        return if (transportCategory.containsMatchIn(category)) -1 else 0
    }

    fun select(query: ChatQuery, entries: List<LedgerEntry>): List<LedgerEntry> {
        validateQuery(query)
        return entries.filter { baseMatch(query, it) && (query.topic != "private_car" || carMatch(it) == 1) }
    }

    fun uncertain(query: ChatQuery, entries: List<LedgerEntry>): List<LedgerEntry> {
        validateQuery(query)
        return if (query.topic != "private_car") emptyList() else entries.filter { baseMatch(query, it) && carMatch(it) == -1 }
    }

    fun privateCarQuery(text: String, today: LocalDate, localRole: String, messages: List<ChatMessage> = emptyList()): ChatQuery? {
        val compact = text.replace(Regex("\\s+"), "").trimEnd('？', '?', '。', '！', '!')
        val previous = messages.asReversed().firstNotNullOfOrNull { message ->
            ConversationMemory.queries(message).takeIf { it.isNotEmpty() }
        }?.lastOrNull()?.takeIf { it.topic == "private_car" }
        val explicit = Regex("养车|用车|私家车|汽车(?:开销|费用|花费|支出)").containsMatchIn(compact) &&
            Regex("多少|统计|查询|明细|看看|花了.*[?？]|费用.*[?？]").containsMatchIn(text)
        val followup = previous != null && Regex("(?:那|那么|再看)?(?:今年|去年|本月|这个月|上月|上个月|老公|老婆|爸爸|妈妈|全家|全部成员|明细|看看明细)(?:的|支出|花费|明细|呢|是多少|多少钱)*").matches(compact)
        if (!explicit && !followup) return null
        // Do not guess unsupported periods or silently discard a requested comparison.
        if (explicit) {
            val remainder = compact.replace(Regex("(?:不包括|不包含|不计入|不计|不算|排除|不含)(?:公共交通|公交|地铁|打车|网约车|出租车|购车|车贷)(?:费用|费|的钱)?"), "")
                .replace(Regex("汽车(?:开销|费用|花费|支出)|全部成员|上个月|这个月|今年|去年|本月|上月|老公|老婆|爸爸|妈妈|全家|家庭|本人|我|请问|想知道|想问|帮忙|帮|请|查一下|查询|统计|看看|一下|注意是|注意|多少钱|多少|花了|花费|花|支出|消费|开销|费用|一共|总共|累计|合计|总计|养车|用车|私家车|明细|呢|的|钱|是|在|上|了|[？?。！!，,：:；;（）()]"), "")
            if (remainder.isNotEmpty()) return null
        }
        val period = when {
            "去年" in compact -> today.minusYears(1).withDayOfYear(1) to today.withDayOfYear(1)
            "上个月" in compact || "上月" in compact -> today.withDayOfMonth(1).minusMonths(1) to today.withDayOfMonth(1)
            "本月" in compact || "这个月" in compact -> today.withDayOfMonth(1) to today.plusDays(1)
            "今年" in compact -> today.withDayOfYear(1) to today.plusDays(1)
            previous != null -> LocalDate.parse(previous.start) to LocalDate.parse(previous.end)
            else -> today.withDayOfYear(1) to today.plusDays(1)
        }
        val named = listOf("老公", "老婆", "爸爸", "妈妈").filter { it in compact }
        if (named.size > 1 || (named.isNotEmpty() && Regex("^(?:我|本人)").containsMatchIn(compact))) return null
        val member = when {
            Regex("全家|全部成员|家庭").containsMatchIn(compact) -> ""
            named.isNotEmpty() -> named.single()
            Regex("我|本人").containsMatchIn(compact) -> role(localRole)
            previous != null -> previous.member
            else -> role(localRole)
        }
        val label = "${period.first} 至 ${period.second.minusDays(1)}养车支出" + if ("明细" in compact) "明细" else ""
        return (previous ?: ChatQuery(period.first.toString(), period.second.toString(), member, "", "", type = "EXPENSE", topic = "private_car"))
            .copy(start = period.first.toString(), end = period.second.toString(), member = member, label = label)
    }

    private fun compactAnswer(query: ChatQuery, rows: List<LedgerEntry>, entries: List<LedgerEntry>): String {
        val income = rows.filter { it.type == EntryType.INCOME }.fold(0L) { sum, e -> Math.addExact(sum, e.amountMinor) }
        val expense = rows.filter { it.type == EntryType.EXPENSE }.fold(0L) { sum, e -> Math.addExact(sum, e.amountMinor) }
        return buildString {
            append("${query.start} 至 ${LocalDate.parse(query.end).minusDays(1)}，${query.member.ifBlank { "全家" }}")
            if (query.topic == "private_car") append("已记录养车支出") else if (query.type == "INCOME") append("已记录收入") else append("已记录支出")
            append(" ¥ ${Money.format(if (query.type == "INCOME") income else expense)}，共 ${rows.size} 笔。")
            if (query.type.isBlank() && query.topic.isBlank()) append("收入 ¥ ${Money.format(income)}。")
            val pending = uncertain(query, entries)
            if (query.topic == "private_car") {
                append("已排除公共交通、打车、购车和车贷。")
                if (pending.isNotEmpty()) append("另有 ${pending.size} 笔交通费归类待确认，未计入金额。")
            }
            if (Regex("明细|逐笔|最近交易|交易记录").containsMatchIn(query.label)) {
                rows.sortedByDescending { it.occurredOn }.take(10).forEach {
                    append("\n${it.occurredOn} ${it.member} ${it.categoryL2.ifBlank { it.categoryL1 }} ¥ ${Money.format(it.amountMinor)}")
                }
                if (rows.size > 10) append("\n仅显示最近十笔。")
            }
            append("\n依据本机已保存账目，未同步记录不在其中。")
        }
    }

    fun answer(query: ChatQuery, entries: List<LedgerEntry>): String {
        val start = LocalDate.parse(validateDate(query.start)); val end = LocalDate.parse(validateDate(query.end))
        val rows = select(query, entries)
        if (advanced(query)) return compactAnswer(query, rows, entries)
        val summary = summarize(rows, start, end)
        return buildString {
            if (query.label.isNotBlank()) appendLine(query.label)
            appendLine("${query.start} 至 ${end.minusDays(1)}")
            if (query.member.isNotBlank()) appendLine("成员：${query.member}")
            if (query.category.isNotBlank()) appendLine("分类：${query.category}")
            if (query.keyword.isNotBlank()) appendLine("关键词：${query.keyword}")
            if (query.type.isNotBlank()) appendLine("收支类型：${EntryType.valueOf(query.type).label}")
            if (query.giftOnly) appendLine("仅礼金")
            if (query.counterparty.isNotBlank()) appendLine("往来人：${query.counterparty}")
            appendLine("共 ${rows.size} 笔收支")
            appendLine("收入 ¥ ${Money.format(summary.income)}")
            appendLine("支出 ¥ ${Money.format(summary.expense)}")
            appendLine("结余 ¥ ${Money.format(summary.balance)}")
            if (summary.categories.isNotEmpty()) {
                appendLine("\n支出分类（最多显示前十项）")
                summary.categories.take(10).forEach { appendLine("${it.name}：¥ ${Money.format(it.amount)}") }
            }
            if (query.member.isBlank()) {
                val members = rows.filter { it.type == EntryType.EXPENSE }.groupBy { it.member }.map { (name, records) ->
                    CategoryTotal(name, records.fold(0L) { total, entry -> Math.addExact(total, entry.amountMinor) })
                }.sortedByDescending { it.amount }
                if (members.isNotEmpty()) {
                    appendLine("\n成员支出（最多显示前十项）")
                    members.take(10).forEach { appendLine("${it.name}：¥ ${Money.format(it.amount)}") }
                }
            }
            if (rows.isNotEmpty()) {
                appendLine("\n最近明细（最多十笔）")
                rows.sortedByDescending { it.occurredOn }.take(10).forEach {
                    appendLine("${it.occurredOn} ${it.member} ${it.categoryL2.ifBlank { it.categoryL1 }} ${it.type.label} ¥ ${Money.format(it.amountMinor)}")
                }
            }
            append("\n依据本机已保存账目，未同步的家庭记录不在其中；结余不等于账户余额。")
        }
    }
}
