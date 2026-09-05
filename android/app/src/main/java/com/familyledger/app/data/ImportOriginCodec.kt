package com.familyledger.app.data

import com.familyledger.app.domain.ImportOrigin
import org.json.JSONObject

object ImportOriginCodec {
    fun encode(origin: ImportOrigin?): String = origin?.let { o -> JSONObject().apply {
        put("fileHash", o.fileHash); put("fileName", o.fileName); put("sheet", o.sheet); put("rowNumber", o.rowNumber)
        put("importedAt", o.importedAt); put("originalDate", o.originalDate); put("account2", o.account2)
        put("projectCategory", o.projectCategory); put("rawFields", JSONObject(o.rawFields))
    }.toString() } ?: ""

    fun decode(text: String): ImportOrigin? {
        if (text.isEmpty()) return null
        val o = JSONObject(text)
        fun str(key: String) = o.get(key) as? String ?: throw IllegalArgumentException("来源 $key 必须为文本")
        fun num(key: String): Long {
            val raw = o.get(key)
            require(raw is Number && Regex("[0-9]+").matches(raw.toString())) { "来源数字无效" }
            return raw.toString().toLong()
        }
        val fields = o.getJSONObject("rawFields")
        val raw = fields.keys().asSequence().associateWith { fields.get(it) as? String ?: throw IllegalArgumentException("原始字段必须为文本") }
        val row = num("rowNumber"); require(row in 1..10001)
        return ImportOrigin(str("fileHash"), str("fileName"), str("sheet"), row.toInt(), num("importedAt"), str("originalDate"), str("account2"), str("projectCategory"), raw)
    }
}
