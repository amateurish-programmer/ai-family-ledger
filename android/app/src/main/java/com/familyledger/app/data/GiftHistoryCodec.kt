package com.familyledger.app.data

import com.familyledger.app.domain.*
import org.json.JSONArray
import org.json.JSONObject

data class GiftCategory(val first: String, val second: String) {
    val label: String get() = if (second.isBlank()) first else "$first / $second"
    val suggested: Boolean get() = listOf("人情", "礼金", "红包", "送礼", "收礼", "随礼").any { it in first || it in second }
}
data class GiftHistoryProposal(val original: LedgerEntry, val selected: Boolean, val counterparty: String)

object GiftHistoryCodec {
    const val BATCH_SIZE = 20
    fun categories(entries: List<LedgerEntry>): List<GiftCategory> = entries.filter(::eligible)
        .map { GiftCategory(it.categoryL1, it.categoryL2) }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
    fun eligible(entry: LedgerEntry): Boolean = entry.deletedAt == null && entry.currency == "CNY" &&
        entry.type != EntryType.BALANCE_ADJUSTMENT && (!entry.isGift || entry.counterparty.isBlank())
    fun candidates(entries: List<LedgerEntry>, categories: Set<GiftCategory>): List<LedgerEntry> = entries
        .filter { eligible(it) && GiftCategory(it.categoryL1, it.categoryL2) in categories }.sortedWith(compareBy({ it.occurredOn }, { it.id }))
    fun batches(rows: List<LedgerEntry>): List<List<LedgerEntry>> {
        val result = mutableListOf<List<LedgerEntry>>()
        var current = mutableListOf<LedgerEntry>()
        for (row in rows) {
            val next = current + row
            if (current.isNotEmpty() && (next.size > BATCH_SIZE || requestBytes(next) > 60000)) {
                result += current.toList()
                current = mutableListOf()
            }
            current += row
            require(requestBytes(current) <= 60000) { "单条备注超过 AI 输入上限" }
        }
        if (current.isNotEmpty()) result += current.toList()
        return result
    }
    private fun requestBytes(rows: List<LedgerEntry>): Int = JSONObject().put("operation", "gift_history")
        .put("input", input(rows)).toString().toByteArray(Charsets.UTF_8).size
    fun input(rows: List<LedgerEntry>): JSONObject {
        require(rows.size in 1..BATCH_SIZE && rows.map { it.id }.toSet().size == rows.size)
        return JSONObject().put("items", JSONArray().apply { rows.forEach { row ->
            put(JSONObject().put("id", row.id).put("merchant", row.merchant).put("note", row.note))
        } })
    }
    fun decode(text: String, originals: List<LedgerEntry>): List<GiftHistoryProposal> {
        require(originals.size in 1..BATCH_SIZE && originals.map { it.id }.toSet().size == originals.size)
        val root = JSONObject(text)
        require(root.keys().asSequence().toSet() == setOf("items")) { "AI 整理格式无效" }
        val rows = root.getJSONArray("items")
        require(rows.length() == originals.size) { "AI 整理结果不完整，请重试" }
        val requested = originals.associateBy { it.id }
        val found = mutableMapOf<String, GiftHistoryProposal>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("id", "isGift", "counterparty")) { "AI 整理字段无效" }
            val id = row.get("id") as? String ?: error("AI 记录 ID 无效")
            val original = requested[id] ?: error("AI 返回未知记录，请重试")
            require(id !in found) { "AI 返回重复记录，请重试" }
            val selected = row.get("isGift") as? Boolean ?: error("AI 礼金标记无效")
            val name = row.get("counterparty") as? String ?: error("AI 往来人格式无效")
            require(name.length <= 100 && (selected || name.isEmpty())) { "AI 往来人无效" }
            found[id] = GiftHistoryProposal(original, selected, name.trim())
        }
        return originals.map { found.getValue(it.id) }
    }
}
