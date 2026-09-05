package com.familyledger.app.data

import com.familyledger.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object BackupCodec {
    const val MAX_BYTES = 10 * 1024 * 1024

    fun encode(entries: List<LedgerEntry>): String {
        val rows = JSONArray()
        entries.forEach { e -> rows.put(JSONObject().apply {
            put("id", e.id); put("type", e.type.name); put("occurredOn", e.occurredOn)
            put("amountMinor", e.amountMinor); put("currency", e.currency)
            put("categoryL1", e.categoryL1); put("categoryL2", e.categoryL2)
            put("account", e.account); put("member", e.member); put("recordedBy", e.recordedBy)
            put("merchant", e.merchant); put("project", e.project); put("note", e.note)
            put("updatedAt", e.updatedAt); put("deletedAt", e.deletedAt ?: JSONObject.NULL)
        }) }
        val text = JSONObject().put("format", "family-ledger").put("version", 1).put("entries", rows).toString()
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份超过 10 MB 上限" }
        return text
    }

    fun decode(text: String): List<LedgerEntry> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份超过 10 MB 上限" }
        try {
            val root = JSONObject(text)
            require(string(root, "format") == "family-ledger" && integer(root, "version") == 1L) { "不支持的备份格式或版本" }
            val rows = root.getJSONArray("entries")
            require(rows.length() <= 100000) { "备份记录过多" }
            val seen = hashSetOf<String>()
            return (0 until rows.length()).map { index ->
                val r = rows.getJSONObject(index)
                val id = string(r, "id")
                require(UUID.fromString(id).toString() == id && seen.add(id)) { "备份包含无效或重复记录 ID" }
                require(r.has("deletedAt")) { "备份缺少删除标记，无法安全恢复" }
                validateEntry(LedgerEntry(id, EntryType.valueOf(string(r, "type")),
                    string(r, "occurredOn"), integer(r, "amountMinor"),
                    string(r, "categoryL1"), string(r, "categoryL2"), string(r, "account"),
                    string(r, "member"), string(r, "recordedBy"), string(r, "merchant"),
                    string(r, "project"), string(r, "note"), string(r, "currency"),
                    integer(r, "updatedAt"), if (r.isNull("deletedAt")) null else integer(r, "deletedAt")))
            }
        } catch (e: IllegalArgumentException) { throw e
        } catch (_: Exception) { throw IllegalArgumentException("备份损坏或字段缺失，未恢复任何记录") }
    }

    private fun string(row: JSONObject, key: String): String =
        row.get(key) as? String ?: throw IllegalArgumentException("$key 必须为文本")

    private fun integer(row: JSONObject, key: String): Long {
        val raw = row.get(key)
        require(raw is Number && Regex("[0-9]+").matches(raw.toString())) { "$key 必须为非负整数" }
        return raw.toString().toLongOrNull() ?: throw IllegalArgumentException("$key 超出范围")
    }
}
