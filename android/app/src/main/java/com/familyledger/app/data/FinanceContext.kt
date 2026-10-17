package com.familyledger.app.data

import com.familyledger.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.math.BigDecimal
import java.math.RoundingMode

/** Bounded, deterministic facts. Notes and account names never leave the device. */
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

    fun buildQueries(entries: List<LedgerEntry>, today: LocalDate, lastSync: String, queries: List<ChatQuery>): JSONObject {
        require(queries.size <= 4) { "一次最多查询四组范围" }
        if (queries.isEmpty()) return build(entries, today, lastSync)
        val facts = JSONArray()
        fun fact(label: String, value: String) {
            require(facts.length() < 160 && label.length <= 100 && value.length <= 500)
            facts.put(JSONObject().put("id", "F${facts.length()}").put("label", label).put("value", value))
        }
        fun amount(label: String, value: Long) = fact(label, "¥ ${Money.format(value)}")
        fun total(rows: List<LedgerEntry>) = rows.fold(0L) { sum, row -> Math.addExact(sum, row.amountMinor) }
        val scope = "本机已保存人民币收支；${queries.size} 组独立筛选，日期左闭右开；各组可能重叠，不得相加为家庭总额"
        fact("统计范围", scope)
        val summaries = queries.mapIndexed { index, query ->
            val rows = ChatCodec.select(query, entries)
            val name = "Q${index + 1}"
            if (query.label.isNotBlank()) fact("$name · 查询标签", query.label)
            fact("$name · 筛选范围", "${query.start} 至 ${LocalDate.parse(query.end).minusDays(1)}；成员=${query.member.ifBlank { "全部" }}；分类=${query.category.ifBlank { "全部" }}；关键词=${query.keyword.ifBlank { "无" }}；类型=${query.type.ifBlank { "全部收支" }}；仅礼金=${query.giftOnly}；往来人=${query.counterparty.ifBlank { "全部" }}")
            fact("$name · 已记录日期", if (rows.isEmpty()) "无记录，不等于实际无收支" else "${rows.minOf { it.occurredOn }} 至 ${rows.maxOf { it.occurredOn }}")
            val end = LocalDate.parse(query.end)
            fact("$name · 期间完整性", when {
                LocalDate.parse(query.start) > today -> "查询期间尚未开始；未来记录不证明交易已发生；不代表记录完整"
                end > today -> "期间尚未结束；当前日期 $today，不能直接与完整期间比较；不代表记录完整"
                else -> "查询期间已到达日历截止日期；不代表记录完整，仍可能缺记或未同步"
            })
            val income = rows.filter { it.type == EntryType.INCOME }
            val expense = rows.filter { it.type == EntryType.EXPENSE }
            val summary = Summary(total(income), total(expense), emptyList())
            fact("$name · 收支笔数", "${rows.size} 笔")
            amount("$name · 收入", summary.income)
            amount("$name · 支出", summary.expense)
            amount("$name · 结余", summary.balance)
            fun ranked(kind: String, selected: List<LedgerEntry>, key: (LedgerEntry) -> String) {
                selected.groupBy(key).map { (key, values) -> key to total(values) }
                    .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
                    .take(2).forEach { (key, value) -> amount("$name · $kind ${key.ifBlank { "未指定" }.take(48)}", value) }
            }
            listOf("收入" to income, "支出" to expense).forEach { (kind, selected) ->
                ranked("${kind}分类", selected) { it.categoryL2.ifBlank { it.categoryL1 } }
                ranked("成员$kind", selected) { it.member }
                ranked("商家$kind", selected) { it.merchant }
            }
            val giftsIn = income.filter { it.isGift }
            val giftsOut = expense.filter { it.isGift }
            amount("$name · 礼金收入", total(giftsIn))
            amount("$name · 礼金支出", total(giftsOut))
            ranked("礼金收入往来人", giftsIn) { it.counterparty }
            ranked("礼金支出往来人", giftsOut) { it.counterparty }
            // Only explicit detail requests expose a few selected fields; never notes/accounts/raw rows.
            if (Regex("明细|逐笔|最近交易|交易记录").containsMatchIn(query.label)) {
                fact("$name · 明细范围", "仅展示最近 ${minOf(5, rows.size)} 笔，共 ${rows.size} 笔；不是完整流水")
                rows.sortedWith(compareByDescending<LedgerEntry> { it.occurredOn }.thenBy { it.id }).take(5).forEachIndexed { i, row ->
                    val gift = if (row.isGift) "；礼金往来人=${row.counterparty.ifBlank { "未指定" }.take(40)}" else ""
                    fact("$name · 明细 ${i + 1}", "${row.occurredOn}；${row.member.take(40)}；${row.type.label} ¥ ${Money.format(row.amountMinor)}；${row.categoryL2.ifBlank { row.categoryL1 }.take(40)}；商家=${row.merchant.ifBlank { "未指定" }.take(40)}$gift")
                }
            }
            summary
        }
        val baseline = summaries.first()
        summaries.drop(1).forEachIndexed { index, summary ->
            val name = "Q${index + 2}"
            listOf(Triple("收入", summary.income, baseline.income), Triple("支出", summary.expense, baseline.expense),
                Triple("结余", summary.balance, baseline.balance)).forEach { (kind, current, previous) ->
                val delta = Math.subtractExact(current, previous)
                amount("$name − Q1 · ${kind}差额", delta)
                fact("$name 相对 Q1 · ${kind}变化率", if (previous <= 0) "基期为零或负数，无法计算有意义的变化率" else
                    BigDecimal.valueOf(delta).multiply(BigDecimal("100")).divide(BigDecimal.valueOf(previous), 2, RoundingMode.HALF_UP).toPlainString() + "%")
            }
        }
        return JSONObject().put("scope", scope).put("facts", facts).put("limitations", JSONArray(listOf(
            "依据本机已保存账目；最近成功同步：${lastSync.take(150)}。未同步家庭记录不在其中；没有记录不等于实际零收支。",
            "只统计人民币收入和支出，排除已删除记录、其他币种和余额调整；结余不是账户余额或净资产。",
            "每组分类、成员、商家和礼金往来人仅显示各方向前两项；金额合计包含该组全部匹配记录。明细仅在明确请求时展示最近五笔，不提供备注或账户。",
            "差额均为后组减 Q1，变化率以 Q1 正值为分母，精确十进制计算后四舍五入至两位；基期为零或负数不提供变化率。",
            "各组日期、成员、分类和类型筛选可能不同或重叠；差额仅为所选记录的比较，不自动代表同比、趋势或因果。尚未结束的期间不得当作完整期间比较。",
            "所有期间都可能缺记；账本不证明完整资产、负债、未来收入或风险承受能力，用户历史陈述也未验证。"
        )))
    }
}
