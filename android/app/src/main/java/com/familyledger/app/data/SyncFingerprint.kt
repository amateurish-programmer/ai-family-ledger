package com.familyledger.app.data

import com.familyledger.app.domain.LedgerEntry
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Default V1.2 metadata must not dirty unchanged V1.1 sync baselines. */
internal object SyncFingerprint {
    fun hash(entry: LedgerEntry): String {
        val encoded = JSONObject(BackupCodec.encode(listOf(entry)))
        if (!entry.isGift && entry.counterparty.isEmpty()) {
            encoded.getJSONArray("entries").getJSONObject(0).apply { remove("isGift"); remove("counterparty") }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical(encoded).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
}
