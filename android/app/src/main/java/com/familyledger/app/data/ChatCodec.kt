package com.familyledger.app.data

import com.familyledger.app.domain.*
import org.json.JSONObject
import java.time.LocalDate

data class ChatQuery(val start: String, val end: String, val member: String, val category: String, val keyword: String,
    val label: String = "", val type: String = "", val giftOnly: Boolean = false, val counterparty: String = "")
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
        return query
    }

    internal fun queryFromJson(q: JSONObject, allowLegacy: Boolean = true): ChatQuery {
        val oldKeys = setOf("start", "end", "member", "category", "keyword")
        val keys = q.keys().asSequence().toSet()
        require(keys == oldKeys + setOf("label", "type", "giftOnly", "counterparty") || (allowLegacy && keys == oldKeys)) { "查询字段无效" }
        fun field(name: String) = q.get(name) as? String ?: throw IllegalArgumentException("查询字段无效")
        val legacy = keys == oldKeys
        return validateQuery(ChatQuery(field("start"), field("end"), field("member"), field("category"), field("keyword"),
            if (legacy) "" else field("label"), if (legacy) "" else field("type"),
            if (legacy) false else q.get("giftOnly") as? Boolean ?: throw IllegalArgumentException("礼金筛选格式无效"),
            if (legacy) "" else field("counterparty")))
    }

    internal fun queryJson(query: ChatQuery): JSONObject = validateQuery(query).let {
        JSONObject().put("start", it.start).put("end", it.end).put("member", it.member).put("category", it.category)
            .put("keyword", it.keyword).put("label", it.label).put("type", it.type).put("giftOnly", it.giftOnly).put("counterparty", it.counterparty)
    }

    fun select(query: ChatQuery, entries: List<LedgerEntry>): List<LedgerEntry> {
        validateQuery(query)
        return entries.filter { e ->
            e.deletedAt == null && e.currency == "CNY" && e.type != EntryType.BALANCE_ADJUSTMENT &&
                e.occurredOn >= query.start && e.occurredOn < query.end &&
                (query.member.isBlank() || e.member == query.member) &&
                (query.category.isBlank() || e.categoryL1 == query.category || e.categoryL2 == query.category) &&
                (query.type.isBlank() || e.type.name == query.type) &&
                (!query.giftOnly || e.isGift) &&
                (query.counterparty.isBlank() || (e.isGift && e.counterparty == query.counterparty)) &&
                (query.keyword.isBlank() || listOf(e.note, e.merchant, e.project, e.account, e.categoryL1, e.categoryL2).any { query.keyword in it })
        }
    }

    fun answer(query: ChatQuery, entries: List<LedgerEntry>): String {
        val start = LocalDate.parse(validateDate(query.start)); val end = LocalDate.parse(validateDate(query.end))
        val rows = select(query, entries)
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
