package com.familyledger.app.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/** Each account/family pair has a separate index and thumbnail directory. */
internal class FamilyAvatarCache(private val context: Context, private val user: String, family: String) {
    private val scope = digest("$user/$family".toByteArray()).take(32)
    private val prefs = context.getSharedPreferences("family_avatar_index", Context.MODE_PRIVATE)
    private val directory = File(context.cacheDir, "family-avatars/$scope")

    fun read(localRole: String? = null): Map<String, String> {
        val json = try { JSONObject(prefs.getString(scope, "{}") ?: "{}") }
            catch (_: org.json.JSONException) { return emptyMap() }
        return json.keys().asSequence().associateWith { role ->
            val entry = json.optJSONObject(role)
            if (entry == null || (role == localRole && !entry.optBoolean("self"))) AMBIGUOUS
            else entry.getString("avatar")
        }
    }

    fun replace(rows: JSONArray) {
        val labels = (0 until rows.length()).map(rows::getJSONObject).groupBy { it.getString("role") }
        val index = JSONObject()
        val retained = mutableSetOf<String>()
        for ((role, matches) in labels) {
            val row = matches.singleOrNull()
            if (row == null) {
                index.put(role, JSONObject().put("avatar", AMBIGUOUS))
                continue
            }
            val choice = row.getString("avatar")
            val entry = JSONObject().put("self", row.getString("user_id") == user)
            if (choice == "photo") {
                val encoded = row.getString("photo")
                require(encoded.length <= 87384) { "家庭头像过大" }
                val bytes = Base64.getDecoder().decode(encoded)
                require(bytes.size in 1..65536) { "家庭头像大小异常" }
                val options = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                if (options.outMimeType != "image/jpeg" || options.outWidth !in 1..512 || options.outHeight !in 1..512) continue
                val hash = digest(bytes)
                val file = File(directory, "$hash.jpg")
                if (!file.isFile) {
                    check(directory.isDirectory || directory.mkdirs()) { "无法缓存家庭头像" }
                    val atomic = AtomicFile(file)
                    val stream = atomic.startWrite()
                    try { stream.write(bytes); atomic.finishWrite(stream) }
                    catch (error: Exception) { atomic.failWrite(stream); throw error }
                }
                retained.add(file.name)
                index.put(role, entry.put("avatar", "shared-$scope-$hash"))
            } else if (IdentityProfile.avatars.any { it.id == choice }) index.put(role, entry.put("avatar", choice))
        }
        check(prefs.edit().putString(scope, index.toString()).commit()) { "家庭头像索引保存失败" }
        directory.listFiles()?.filter { it.extension == "jpg" && it.name !in retained }?.forEach { it.delete() }
    }

    companion object {
        const val AMBIGUOUS = "ambiguous"
        private val sharedId = Regex("shared-([0-9a-f]{32})-([0-9a-f]{64})")
        private fun digest(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun isPhoto(id: String) = sharedId.matches(id)
        fun file(context: Context, id: String): File? {
            val match = sharedId.matchEntire(id) ?: return null
            return File(context.cacheDir, "family-avatars/${match.groupValues[1]}/${match.groupValues[2]}.jpg").takeIf { it.isFile }
        }
    }
}
