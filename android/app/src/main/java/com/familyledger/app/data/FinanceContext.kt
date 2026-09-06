package com.familyledger.app.data

import com.familyledger.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

/** A bounded, deterministic digest. No notes, merchants, accounts or raw ledger are sent. */
object FinanceContext {
    fun build(entries: List<LedgerEntry>, today: LocalDate, lastSync: String, query: ChatQuery? = null): JSONObject {
        val rows = if (query != null) ChatCodec.select(query, entries) else entries.filter {
            it.deletedAt == null && it.currency == "CNY" && it.type != EntryType.BALANCE_ADJUSTMENT
        }
        val facts = JSONArray()
        fun fact(label: String, value: String) {
            facts.put(JSONObject().put("id", "F${facts.length()}").put("label", label).put("value", value))
        }
        fun amount(label: String, value: Long) = fact(label, "¥ ${Money.format(value)}")
        fun totals(label: String, selected: List<LedgerEntry>) {
            var income = 0L; var expense = 0L
            selected.forEach { if (it.type == EntryType.INCOME) income = Math.addExact(income, it.amountMinor)
                else expense = Math.addExact(expense, it.amountMinor) }
            fact("$label · 收支笔数", "${selected.size} 笔")
            amount("$label · 收入", income); amount("$label · 支出", expense)
            amount("$label · 结余", Math.subtractExact(income, expense))
        }
        fun groups(label: String, selected: List<LedgerEntry>) {
            val expenses = selected.filter { it.type == EntryType.EXPENSE }
            fun grouped(kind: String, key: (LedgerEntry) -> String) {
                expenses.groupBy(key).map { (name, values) -> name to values.fold(0L) { total, e -> Math.addExact(total, e.amountMinor) } }
                    .sortedByDescending { it.second }.take(8).forEach { (name, value) -> amount("$label · $kind ${name.take(32)}", value) }
            }
            grouped("支出分类", { it.categoryL1 }); grouped("成员支出", { it.member })
        }
        val scope = if (query == null) "本机全部已保存人民币收支" else
            "${query.start} 至 ${LocalDate.parse(query.end).minusDays(1)}，成员：${query.member.ifBlank { "全部" }}，分类：${query.category.ifBlank { "全部" }}，关键词：${query.keyword.ifBlank { "无" }}"
        fact("统计范围", scope)
        if (rows.isNotEmpty()) fact("已记录收支的日期范围", "${rows.minOf { it.occurredOn }} 至 ${rows.maxOf { it.occurredOn }}")
        totals("选定范围合计", rows); groups("选定范围", rows)
        val current = YearMonth.from(today)
        val recent = (11 downTo 0).map { current.minusMonths(it.toLong()).toString() }.toSet()
        val months = rows.groupBy { it.occurredOn.take(7) }
        // Include empty recent months explicitly: zero recorded transactions is not zero real spending.
        recent.forEach { month -> totals(month, months[month].orEmpty()) }
        val years = rows.groupBy { it.occurredOn.take(4) }.toSortedMap().entries.toList().takeLast(12)
        years.forEach { (year, selected) -> totals("${year}年", selected) }
        groups("本月 ${current}", months[current.toString()].orEmpty())
        groups("今年 ${today.year}", rows.filter { it.occurredOn.take(4) == today.year.toString() })
        return JSONObject().put("scope", scope).put("facts", facts).put("limitations", JSONArray(listOf(
            "依据本机已保存账目；最近成功同步：$lastSync。未同步家庭记录不在其中。",
            "只统计人民币收入和支出，排除已删除记录、其他币种与余额调整；结余不代表现金余额或净资产。",
            "分类与成员仅展示支出前八项；按月展示最近十二个月，按年展示最近十二个有记录年份。范围合计覆盖所选全部记录。",
            "无记录月份不等于实际零收入或零消费；本月和本年可能尚未结束，不能直接与完整期间作增减结论。",
            "账本未提供完整资产、负债利率、投资持仓、保险、未来收入稳定性和风险承受能力；建议需结合这些信息。"
        )))
    }
}
