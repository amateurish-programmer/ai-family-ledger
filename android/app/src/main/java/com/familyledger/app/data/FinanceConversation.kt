package com.familyledger.app.data

import com.familyledger.app.domain.LedgerEntry
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
        val history = ConversationMemory.history(messages)
        val memory = ConversationMemory.memory(messages)
        val finance = withContext(Dispatchers.Default) { FinanceContext.build(rows, today, lastSync) }
        val input = JSONObject().put("text", text).put("today", today.toString()).put("role", role)
            .put("history", history).put("memory", memory).put("analysisV2", true)
            .put("finance", finance).put("giftFields", true)
            .put("members", JSONArray(rows.map { it.member }.distinct().take(20)))
            .put("categories", JSONArray(rows.flatMap { listOf(it.categoryL1, it.categoryL2) }.filter { it.isNotBlank() }.distinct().take(20)))
        val result = ChatCodec.decode(ai("chat", boundedInput("chat", input)), role)
        val queries = result.queries.ifEmpty { listOfNotNull(result.query) }
        if (queries.isEmpty()) return result
        onQuery()
        val selected = withContext(Dispatchers.Default) { FinanceContext.buildQueries(rows, today, lastSync, queries) }
        val answer = try {
            ai("analyze", boundedInput("analyze", JSONObject().put("text", text).put("history", history).put("memory", memory)
                .put("analysisV2", true).put("finance", selected)))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            "AI 解读暂未完成：${e.message ?: "服务暂不可用"}。以下为程序计算的查询结果：\n\n" +
                withContext(Dispatchers.Default) { queries.joinToString("\n\n") { ChatCodec.answer(it, rows) } }
        }
        return result.copy(reply = answer, query = null, queries = queries)
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
