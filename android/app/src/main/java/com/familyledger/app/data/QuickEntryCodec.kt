package com.familyledger.app.data

import com.familyledger.app.domain.*
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

object QuickEntryCodec {
    fun local(text: String, today: LocalDate = LocalDate.now()): List<LedgerEntry> {
        require(text.isNotBlank() && text.length <= 2000) { "请输入 1 至 2000 字的记账内容" }
        val segments = text.split(Regex("[，,；;。\n]+" )).map { it.trim() }.filter { it.isNotEmpty() }
        require(segments.size in 1..20) { "一次最多整理 20 笔" }
        return segments.map { segment ->
            require(!Regex("上周|上月|去年|下周|明天|后天").containsMatchIn(segment)) { "简单整理暂不支持该日期说法，请使用具体日期或云端 AI" }
            val explicitDate = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").find(segment)?.value
            val date = explicitDate?.let(::validateDate) ?: when {
                "前天" in segment -> today.minusDays(2).toString()
                "昨天" in segment -> today.minusDays(1).toString()
                else -> today.toString()
            }
            val withoutDate = if (explicitDate != null) segment.replace(explicitDate, "") else segment
            require(!Regex("-[0-9]|退款|转账|余额|借入|借出").containsMatchIn(withoutDate)) { "该句金额方向不明确，请手工记账或用云端 AI 整理后核对" }
            val numbers = Regex("[0-9]+(?:\\.[0-9]+)?").findAll(withoutDate).toList()
            require(numbers.size == 1) { "每句请写一笔金额，例如：午饭 36.80 元；复杂内容可用云端 AI" }
            require(!Regex("^\\s*(个|件|斤|公斤|袋|次|张)").containsMatchIn(withoutDate.substring(numbers.single().range.last + 1))) { "请写出金额，数量不能直接作为金额" }
            val money = Money.parse(numbers.single().value)
            val income = listOf("工资", "奖金", "收入", "到账", "收款").any { it in segment }
            val category = if (income) "职业收入" else when {
                listOf("饭", "菜", "餐", "早餐", "水果", "奶茶", "咖啡").any { it in segment } -> "食品酒水"
                listOf("车", "地铁", "公交", "充电", "加油").any { it in segment } -> "行车交通"
                listOf("药", "医院", "看病").any { it in segment } -> "医疗保健"
                listOf("房租", "水费", "电费", "燃气").any { it in segment } -> "居家物业"
                else -> "其他支出"
            }
            val account = listOf("支付宝", "微信", "现金", "银行卡").firstOrNull { it in segment } ?: "银行卡"
            validateEntry(LedgerEntry(UUID.randomUUID().toString(), if (income) EntryType.INCOME else EntryType.EXPENSE,
                date, money, category, account = account, member = "本人", recordedBy = "本人", note = segment))
        }
    }

    fun cloud(text: String): List<LedgerEntry> {
        try {
            val rows = JSONObject(text).getJSONArray("entries")
            require(rows.length() in 1..20) { "AI 返回的记录数量无效" }
            return (0 until rows.length()).map { index ->
                val o = rows.getJSONObject(index)
                fun field(key: String) = o.get(key) as? String ?: throw IllegalArgumentException("AI 返回字段 $key 格式无效")
                val type = EntryType.valueOf(field("type"))
                require(type != EntryType.BALANCE_ADJUSTMENT) { "AI 不能创建余额变更" }
                validateEntry(LedgerEntry(UUID.randomUUID().toString(), type, validateDate(field("date")), Money.parse(field("amount")),
                    field("category"), field("subcategory"), field("account"), field("member"), field("recordedBy"),
                    field("merchant"), field("project"), field("note")))
            }
        } catch (e: IllegalArgumentException) { throw e
        } catch (_: Exception) { throw IllegalArgumentException("AI 返回内容不符合记账格式，请修改原文后重试") }
    }
}
