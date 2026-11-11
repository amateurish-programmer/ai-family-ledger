package com.familyledger.app.data

import com.familyledger.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** A bounded planner -> local facts -> explanation flow. It never writes ledger entries. */
object FinanceConversation {
    suspend fun respond(
        text: String, today: LocalDate, role: String, messages: List<ChatMessage>,
        rows: List<LedgerEntry>, lastSync: String,
        onQuery: () -> Unit = {}, ai: suspend (String, JSONObject) -> String,
    ): ChatResult {
        val localQuery = ChatCodec.privateCarQuery(text, today, role, messages)
        if (localQuery != null) {
            onQuery()
            return analyzeQueries(text, today, messages, rows, lastSync, listOf(localQuery), ai = ai)
        }
        val history = ConversationMemory.history(messages)
        val memory = ConversationMemory.memory(messages)
        val finance = withContext(Dispatchers.Default) { FinanceContext.build(rows, today, lastSync) }
        val input = JSONObject().put("text", text).put("today", today.toString()).put("role", role)
            .put("history", history).put("memory", memory).put("analysisV2", true).put("queryV3", true)
            .put("finance", finance).put("giftFields", true)
            .put("members", JSONArray(rows.map { it.member }.filter(String::isNotBlank).distinct().take(200)))
            .put("categories", JSONArray(rows.flatMap { listOf(it.categoryL1, it.categoryL2) }.filter { it.isNotBlank() }.distinct().take(200)))
            .put("categoryTree", categoryTree(rows))
        val result = ChatCodec.decode(ai("chat", boundedInput("chat", input)), role)
        val queries = result.queries.ifEmpty { listOfNotNull(result.query) }
        if (queries.isEmpty()) return result.copy(entries = result.entries.map { CategoryCatalog.normalize(it, rows) })
        onQuery()
        return analyzeQueries(text, today, messages, rows, lastSync, queries, ai = ai)
    }

    internal fun categoryTree(rows: List<LedgerEntry>): JSONArray = JSONArray(
        listOf(EntryType.EXPENSE, EntryType.INCOME).flatMap { type ->
            CategoryCatalog.canonicalChoices(type, rows).map { (parent, children) ->
                JSONObject().put("type", type.name).put("category", parent).put("subcategories", JSONArray(children))
            }
        }
    )

    suspend fun analyzeQueries(
        text: String, today: LocalDate, messages: List<ChatMessage>, rows: List<LedgerEntry>, lastSync: String,
        queries: List<ChatQuery>, suppliedFinance: JSONObject? = null,
        ai: suspend (String, JSONObject) -> String,
    ): ChatResult {
        require(queries.isNotEmpty() && queries.size <= 4)
        val selected = suppliedFinance ?: withContext(Dispatchers.Default) { FinanceContext.buildQueries(rows, today, lastSync, queries) }
        val answer = try {
            ai("analyze", boundedInput("analyze", JSONObject().put("text", text)
                .put("history", ConversationMemory.history(messages)).put("memory", ConversationMemory.memory(messages))
                .put("analysisV2", true).put("queryV3", true).put("finance", selected)))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            "以下为程序计算的查询结果，AI 解读可稍后重试。\n\n" +
                withContext(Dispatchers.Default) { queries.joinToString("\n\n") { ChatCodec.answer(it, rows) } }
        }
        return ChatResult(reply = answer, entries = emptyList(), query = null, queries = queries)
    }

    /** Limit UTF-8 bytes, not characters. Never truncate current text, filters or computed facts. */
    internal fun boundedInput(operation: String, source: JSONObject): JSONObject {
        val input = JSONObject(source.toString())
        fun fits() = JSONObject().put("operation", operation).put("input", input).toString()
            .toByteArray(Charsets.UTF_8).size <= 60 * 1024
        if (fits()) return input
        val history = input.getJSONArray("history")
        val memory = input.getJSONArray("memory")
        for (limit in listOf(1000, 750, 500, 300)) {
            for (index in 0 until history.length()) {
                val turn = history.getJSONObject(index)
                turn.put("text", turn.getString("text").take(limit))
            }
            if (fits()) return input
        }
        while (history.length() > 2 && !fits()) history.remove(0)
        while (memory.length() > 1 && !fits()) memory.remove(0)
        require(fits()) { "本次查询内容较多，请缩小查询范围后重试" }
        return input
    }
}
