package com.familyledger.app.data

import com.familyledger.app.domain.*
import org.json.JSONObject
import java.time.LocalDate

data class ChatQuery(val start: String, val end: String, val member: String, val category: String, val keyword: String)
data class ChatResult(val reply: String, val entries: List<LedgerEntry>, val query: ChatQuery?)
data class ChatMessage(val id: String, val user: Boolean, val text: String, val context: String = text)

object ChatCodec {
    fun role(value: String): String = value.trim().also {
        require(it.length in 1..20 && it.none { c -> c.isISOControl() }) { "角色须为 1 至 20 字，例如老公、老婆" }
    }

    fun decode(text: String, localRole: String): ChatResult {
        val who = role(localRole)
        val root = JSONObject(text)
        require(root.keys().asSequence().toSet() == setOf("reply", "entries", "query")) { "回复格式无效，请重试" }
        val reply = root.get("reply") as? String ?: error("回复格式无效")
        require(reply.isNotBlank() && reply.length <= 3000)
        val raw = root.getJSONArray("entries")
        val entries = if (raw.length() == 0) emptyList() else QuickEntryCodec.cloud(JSONObject().put("entries", raw).toString()).map {
            it.copy(member = if (it.member.trim() in setOf("本人", "我", "未指定")) who else it.member, recordedBy = who)
        }
        val query = if (root.isNull("query")) null else root.getJSONObject("query").let { q ->
            require(q.keys().asSequence().toSet() == setOf("start", "end", "member", "category", "keyword"))
            fun field(name: String): String = (q.get(name) as? String ?: error("查询字段无效")).also { require(it.length <= 100) }
            val start = validateDate(field("start")); val end = validateDate(field("end"))
            require(start < end) { "查询日期范围无效" }
            ChatQuery(start, end, field("member"), field("category"), field("keyword"))
        }
        require(entries.isEmpty() || query == null) { "请将记账与账本查询分开发送" }
        return ChatResult(reply, entries, query)
    }

    fun answer(query: ChatQuery, entries: List<LedgerEntry>): String {
        val start = LocalDate.parse(validateDate(query.start)); val end = LocalDate.parse(validateDate(query.end))
        require(start < end)
        val rows = entries.filter { e ->
            e.deletedAt == null && e.currency == "CNY" && e.type != EntryType.BALANCE_ADJUSTMENT &&
                e.occurredOn >= query.start && e.occurredOn < query.end &&
                (query.member.isBlank() || e.member == query.member) &&
                (query.category.isBlank() || e.categoryL1 == query.category || e.categoryL2 == query.category) &&
                (query.keyword.isBlank() || listOf(e.note, e.merchant, e.project, e.account, e.categoryL1, e.categoryL2).any { query.keyword in it })
        }
        val summary = summarize(rows, start, end)
        return buildString {
            appendLine("${query.start} 至 ${end.minusDays(1)}")
            if (query.member.isNotBlank()) appendLine("成员：${query.member}")
            if (query.category.isNotBlank()) appendLine("分类：${query.category}")
            if (query.keyword.isNotBlank()) appendLine("关键词：${query.keyword}")
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
