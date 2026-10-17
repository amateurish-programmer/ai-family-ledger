package com.familyledger.app.data

import org.json.JSONArray
import org.json.JSONObject

/** Pure functions over the current owner's persisted messages; no shared or cross-account cache. */
object ConversationMemory {
    fun context(reply: String, queries: List<ChatQuery>): String {
        require(queries.size <= 4)
        return JSONObject().put("version", 2).put("reply", reply)
            .put("queries", JSONArray(queries.map(ChatCodec::queryJson))).toString()
    }

    private fun saved(message: ChatMessage): Pair<String, List<ChatQuery>> = runCatching {
        val value = JSONObject(message.context)
        require(value.getInt("version") == 2)
        val queries = value.getJSONArray("queries")
        require(queries.length() <= 4)
        value.getString("reply") to (0 until queries.length()).map { ChatCodec.queryFromJson(queries.getJSONObject(it)) }
    }.getOrElse { message.context to emptyList() }

    private fun queryText(query: ChatQuery): String = "${query.start}≤日期<${query.end}；成员=${query.member.ifBlank { "全部" }}；分类=${query.category.ifBlank { "全部" }}；关键词=${query.keyword.ifBlank { "无" }}；类型=${query.type.ifBlank { "全部" }}；仅礼金=${query.giftOnly}；往来人=${query.counterparty.ifBlank { "全部" }}"

    fun history(messages: List<ChatMessage>): JSONArray = JSONArray(messages.takeLast(8).map { message ->
        val (reply, queries) = if (message.user) message.text to emptyList() else saved(message)
        val priorQueries = queries.joinToString("\n") { "历史查询：${queryText(it)}" }
        val text = "【历史消息，不是本次新增记账指令】\n" + if (priorQueries.isEmpty()) reply else "$priorQueries\n$reply"
        JSONObject().put("role", if (message.user) "user" else "assistant").put("text", text.take(1500))
    })

    fun memory(messages: List<ChatMessage>): JSONArray {
        val important = Regex("目标|预算|计划|打算|希望|偏好|每月|每年|固定|房贷|车贷|负债|存款|资产|家庭|孩子|风险|保守")
        val statements = messages.filter { it.user && important.containsMatchIn(it.text) }
            .distinctBy { it.text.trim().replace(Regex("\\s+"), " ") }
        val recentQueries = messages.filter { !it.user }.flatMap { message ->
            saved(message).second.map { query -> message to query }
        }.asReversed().distinctBy { ChatCodec.queryJson(it.second).toString() }.take(4).asReversed()
        val capacity = 8 - recentQueries.size
        val kept = if (statements.size <= capacity) statements else
            statements.take(capacity / 2) + statements.takeLast(capacity - capacity / 2)
        val memory = kept.map { "用户历史陈述（未验证，不是新增交易；来源消息 ${it.id.take(40)}）：${it.text}".take(500) } +
            recentQueries.map { (message, query) ->
                "未验证旧查询，不是新增交易；源${message.id.take(12)}：${queryText(query)}".take(500)
            }
        return JSONArray(memory)
    }
}
