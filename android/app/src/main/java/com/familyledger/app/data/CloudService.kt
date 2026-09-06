package com.familyledger.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.familyledger.app.domain.LedgerEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class CloudStatus(val configured: Boolean, val email: String?, val familyId: String?, val familyName: String?, val lastSync: Long)
data class CloudConflict(val id: String, val local: LedgerEntry, val remote: LedgerEntry, val remoteRevision: Long)
data class SyncResult(val uploaded: Int, val downloaded: Int, val conflicts: List<CloudConflict>, val elapsedMillis: Long = 0, val requestCount: Int = 0)

/** Manual, opt-in synchronization. The caller must block local edits while passing a snapshot. */
class CloudService(context: Context) {
    private val config = context.applicationContext.getSharedPreferences("cloud_public", Context.MODE_PRIVATE)
    private val secrets = context.applicationContext.getSharedPreferences("cloud_encrypted", Context.MODE_PRIVATE)

    private data class SyncMetrics(val version: Long, var requests: Int = 0)
    private var syncMetrics: SyncMetrics? = null

    companion object {
        private const val KEY_ALIAS = "family_ledger_cloud_aes_v1"
        private const val MAX_RESPONSE = 12 * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 768 * 1024
        private val operations = Mutex()
        private val stateGuard = Any()
        private val logoutScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var epoch = 0L
    }

    fun status(): CloudStatus = synchronized(stateGuard) {
        ensureBuiltInConfiguration()
        val state = readState()
        CloudStatus(config.getString("url", null) != null && config.getString("anonKey", null) != null,
            if (state.has("access")) state.optString("email").ifBlank { null } else null,
            state.optString("familyId").ifBlank { null }, state.optString("familyName").ifBlank { null }, state.optLong("lastSync"))
    }

    fun autoSyncEnabled(): Boolean = config.getBoolean("autoSync", false)
    private fun roleKey(): String {
        val state = readState()
        val owner = state.optString("boundUser").ifBlank { state.optString("user").ifBlank { "local" } }
        return "localRole:$owner"
    }
    fun localRole(): String = synchronized(stateGuard) { config.getString(roleKey(), "本人") ?: "本人" }
    fun setLocalRole(value: String) = synchronized(stateGuard) {
        check(config.edit().putString(roleKey(), ChatCodec.role(value)).commit()) { "角色保存失败" }
    }
    fun setAutoSyncEnabled(enabled: Boolean) {
        check(config.edit().putBoolean("autoSync", enabled).commit()) { "无法保存自动同步选项" }
    }

    private fun ensureBuiltInConfiguration() {
        val previous = config.getString("url", null)
        val state = readState()
        val hasIdentity = listOf("access", "user", "boundUser", "pendingUser", "familyId").any(state::has) ||
            config.all.keys.any { it.startsWith("index:") }
        CloudEndpoint.requireCompatible(previous, hasIdentity)
        if (previous != CloudEndpoint.URL || config.getString("anonKey", null) != CloudEndpoint.PUBLIC_KEY) {
            configure(CloudEndpoint.URL, CloudEndpoint.PUBLIC_KEY)
        }
    }

    private fun configure(url: String, anonKey: String): Unit = synchronized(stateGuard) {
        val normalized = officialUrl(url)
        val key = anonKey.trim()
        require(key.length in 20..4096 && !key.any { it.isWhitespace() }) { "请输入 Supabase 的公开 anon / publishable key" }
        if (key.startsWith("eyJ")) {
            val role = runCatching { JSONObject(String(Base64.decode(key.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)).optString("role") }.getOrDefault("")
            require(role == "anon") { "仅允许公开 anon key，不能使用 service_role 或用户 token" }
        } else require(key.startsWith("sb_publishable_")) { "请输入公开 anon / publishable key" }
        val state = readState()
        val previous = config.getString("url", null)
        require((!state.has("boundUser") && !state.has("pendingUser")) || previous == normalized) { "本机账本已绑定或正在确认此云项目归属，不能更换；请先备份并使用独立应用数据" }
        if (previous != null && previous != normalized) {
            require(!state.has("access")) { "请先退出当前账号再更换云项目" }
        }
        check(config.edit().putString("url", normalized).putString("anonKey", key).commit()) { "无法保存云配置" }
        epoch++
        Unit
    }

    suspend fun signUp(email: String, password: String): String = operation { version ->
        require(password.length in 8..256 && email.trim().length in 3..254) { "邮箱无效，密码须为 8 至 256 个字符" }
        val state = state(version)
        require(!state.has("boundUser") && !state.has("pendingUser")) { "本机已绑定或正在确认账号归属，请使用原账号登录；新账号需要独立应用数据" }
        val response = json(request("POST", "/auth/v1/signup", JSONObject().put("email", email.trim()).put("password", password), null, version))
        if (response.optString("access_token").isNotEmpty()) {
            acceptSession(response, version)
            recoverFamily(version, required = false)
            "注册并登录成功；请创建或加入家庭"
        } else "注册请求已提交，请检查邮箱并完成验证后登录（已注册邮箱可能不会重复创建）"
    }

    suspend fun login(email: String, password: String) = operation { version ->
        require(email.trim().length in 3..254 && password.length in 1..256) { "请输入邮箱和密码" }
        val response = json(request("POST", "/auth/v1/token?grant_type=password", JSONObject().put("email", email.trim()).put("password", password), null, version))
        acceptSession(response, version)
        recoverFamily(version, required = false)
        Unit
    }

    /** Local logout immediately removes both tokens. Permanent account/family binding is retained. */
    fun logout() = synchronized(stateGuard) {
        val state = readState()
        val access = state.optString("access")
        listOf("access", "refresh", "expires", "user", "email").forEach(state::remove)
        writeState(state)
        epoch++
        val version = epoch
        if (access.isNotBlank()) logoutScope.launch {
            // Local logout succeeds offline; revoke this remote session when reachable.
            runCatching { request("POST", "/auth/v1/logout?scope=local", null, access, version) }
        }
        Unit
    }

    suspend fun createFamily(name: String): Unit = operation { version ->
        require(name.trim().length in 1..80) { "家庭名称须为 1 至 80 个字符" }
        if (recoverFamily(version, required = false) == null) {
            markFamilyAttempt(version)
            bindFamily(json(rpc("create_family", JSONObject().put("p_name", name.trim()), version)), version)
        }
    }

    suspend fun joinFamily(inviteCode: String): Unit = operation { version ->
        require(Regex("[0-9a-f]{64}").matches(inviteCode.trim())) { "邀请码须为完整的 64 位代码" }
        val existing = recoverFamily(version, required = false)
        require(existing == null) { "此账号已属于一个家庭，不能切换；请先备份并使用独立应用数据" }
        markFamilyAttempt(version)
        bindFamily(json(rpc("join_family", JSONObject().put("p_code", inviteCode.trim()), version)), version)
    }

    suspend fun createInvite(): String = operation { version ->
        recoverFamily(version, required = true)
        (JSONTokener(rpc("create_invite", JSONObject(), version)).nextValue() as? String
            ?: error("邀请码响应格式无效")).also { require(Regex("[0-9a-f]{64}").matches(it)) { "邀请码响应无效" } }
    }

    suspend fun sync(local: List<LedgerEntry>, applyRemote: suspend (List<LedgerEntry>) -> Unit, onProgress: (String) -> Unit = {}): SyncResult = operation { version ->
        val started = System.nanoTime()
        val metrics = SyncMetrics(version)
        syncMetrics = metrics
        try {
            onProgress("检查家庭权限")
            val family = recoverFamily(version, required = true)!!
            BackupCodec.decode(BackupCodec.encode(local))
            val indexes = indexes(version)
            onProgress("检查云端版本清单")
            val manifest = pullManifest(family, version)
            val result = SyncEngine(
                hash = ::hash,
                uploadBytes = { uploadJson(it).toString().toByteArray(Charsets.UTF_8).size + 1 },
                fetch = { ids -> fetchBatch(family, ids, version) },
                push = { rows -> pushBatch(family, rows, version) },
                applyRemote = applyRemote,
                remember = { values -> rememberBatch(values, version) },
                progress = onProgress,
            ).run(local, manifest, indexes)
            mutate(version) { it.put("lastSync", System.currentTimeMillis()) }
            result.copy(elapsedMillis = (System.nanoTime() - started) / 1_000_000, requestCount = metrics.requests)
        } finally { syncMetrics = null }
    }

    suspend fun resolve(conflict: CloudConflict, useRemote: Boolean, applyRemote: suspend (List<LedgerEntry>) -> Unit): Unit = operation { version ->
        val family = recoverFamily(version, required = true)!!
        require(conflict.id == conflict.local.id && conflict.id == conflict.remote.id && conflict.remoteRevision > 0) { "冲突数据无效" }
        // Re-read before resolving: a displayed conflict is not permission to overwrite later edits.
        val latest = fetchOne(family, conflict.id, version) ?: error("云端记录已不存在，请重新同步")
        require(latest.revision == conflict.remoteRevision && hash(latest.entry) == hash(conflict.remote)) { "云端记录再次变化，请重新同步后选择" }
        if (useRemote) {
            applyRemote(listOf(latest.entry))
            remember(conflict.id, latest.revision, hash(latest.entry), version)
        } else {
            require(push(family, conflict.local, latest.revision, version) == null) { "云端记录再次变化，请重新同步后选择" }
        }
    }

    suspend fun ai(operation: String, input: JSONObject): String = this.operation { version ->
        require(operation in setOf("parse", "report", "chat", "analyze")) { "不支持的 AI 操作" }
        recoverFamily(version, required = true)
        val body = JSONObject().put("operation", operation).put("input", input)
        require(body.toString().toByteArray(Charsets.UTF_8).size <= 65536) { "AI 输入过长" }
        val result = json(authorized("POST", "/functions/v1/ledger-ai", body, version)).get("result")
        require(result is String && result.length <= 20000) { "AI 响应格式无效" }
        result
    }


    private suspend fun <T> operation(block: suspend (Long) -> T): T = withContext(Dispatchers.IO) {
        operations.withLock { block(synchronized(stateGuard) { ensureBuiltInConfiguration(); epoch }) }
    }

    private fun pullManifest(family: String, version: Long): List<SyncManifest> {
        val result = mutableListOf<SyncManifest>()
        var cursor: String? = null
        var bytes = 0L
        while (true) {
            val rows = JSONArray(rpc("get_ledger_manifest", JSONObject().put("p_family", family)
                .put("p_after", cursor ?: JSONObject.NULL), version))
            require(rows.length() <= 500) { "云端清单分页无效" }
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val id = row.getString("id")
                require(UUID.fromString(id).toString() == id && (cursor == null || id > cursor!!)) { "云端清单顺序无效" }
                val size = row.getInt("payload_bytes")
                val revision = row.getLong("revision")
                require(size in 1..MAX_ENTRY_BYTES && revision > 0) { "云端清单无效" }
                bytes += size
                require(bytes <= BackupCodec.MAX_BYTES && result.size < 100000) { "云端账本超过本版本 10 MB / 100000 条同步上限" }
                result += SyncManifest(id, revision, size)
                cursor = id
            }
            if (rows.length() < 500) break
        }
        return result
    }

    private fun fetchBatch(family: String, ids: List<String>, version: Long): List<SyncRemote> {
        require(ids.size in 1..50 && ids.all { UUID.fromString(it).toString() == it })
        val rows = JSONArray(authorized("GET", "/rest/v1/ledger_entries?select=id,payload,revision&family_id=eq.$family&id=in.(" + ids.joinToString(",") + ")&limit=50", null, version))
        return (0 until rows.length()).map { remote(rows.getJSONObject(it)) }
    }

    private fun uploadJson(row: SyncUpload): JSONObject {
        val payload = BackupCodec.encode(listOf(row.entry))
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_ENTRY_BYTES) { "单条账目及导入来源过大" }
        return JSONObject().put("id", row.entry.id).put("payload", payload).put("expected_revision", row.expected)
    }

    private fun pushBatch(family: String, rows: List<SyncUpload>, version: Long): List<SyncUploadResult> {
        val response = JSONArray(rpc("put_ledger_entries", JSONObject().put("p_family", family)
            .put("p_entries", JSONArray().apply { rows.forEach { put(uploadJson(it)) } }), version))
        require(response.length() == rows.size) { "批量上传响应无效" }
        val requested = rows.associateBy { it.entry.id }
        return (0 until response.length()).map { i ->
            val result = response.getJSONObject(i)
            val id = result.getString("id")
            require(id in requested) { "批量上传响应 ID 无效" }
            if (result.getBoolean("ok")) SyncUploadResult(id, result.getLong("revision")) else {
                require(!result.isNull("revision")) { "云端记录消失，请停止同步并核查服务端" }
                val latest = fetchOne(family, id, version) ?: error("云端记录消失，请停止同步并核查服务端")
                require(latest.revision >= result.getLong("revision")) { "云端版本回退，请停止同步" }
                SyncUploadResult(id, latest.revision, latest)
            }
        }
    }

    private fun fetchOne(family: String, id: String, version: Long): SyncRemote? {
        require(UUID.fromString(id).toString() == id)
        val rows = JSONArray(authorized("GET", "/rest/v1/ledger_entries?select=id,payload,revision&family_id=eq.$family&id=eq.$id&limit=1", null, version))
        return if (rows.length() == 0) null else remote(rows.getJSONObject(0))
    }

    private fun remote(row: JSONObject): SyncRemote {
        val entry = BackupCodec.decode(row.getString("payload")).single()
        require(entry.id == row.getString("id")) { "云端记录 ID 不匹配" }
        val revision = row.getLong("revision")
        require(revision > 0) { "云端版本无效" }
        return SyncRemote(entry, revision)
    }

    private fun push(family: String, entry: LedgerEntry, expected: Long, version: Long): CloudConflict? {
        val payload = BackupCodec.encode(listOf(entry))
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_ENTRY_BYTES) { "单条账目及导入来源过大" }
        val response = json(rpc("put_ledger_entry", JSONObject().put("p_family", family).put("p_id", entry.id).put("p_payload", payload).put("p_expected_revision", expected), version))
        if (response.getBoolean("ok")) {
            val revision = response.getLong("revision")
            require(revision > expected) { "服务器版本无效" }
            remember(entry.id, revision, hash(entry), version)
            return null
        }
        require(!response.isNull("payload")) { "云端记录消失，请停止同步并核查服务端" }
        val other = BackupCodec.decode(response.getString("payload")).single()
        require(other.id == entry.id && response.getLong("revision") > 0) { "冲突响应无效" }
        return CloudConflict(entry.id, entry, other, response.getLong("revision"))
    }

    private fun recoverFamily(version: Long, required: Boolean): String? {
        val raw = rpc("get_my_family", JSONObject(), version)
        if (raw.trim() == "null" || raw.isBlank()) {
            require(!state(version).has("familyId")) { "当前账号已失去绑定家庭成员权限，未同步" }
            mutate(version) { if (!it.optBoolean("pendingAction")) it.remove("pendingUser") }
            require(!required) { "请先创建或加入家庭" }
            return null
        }
        val family = json(raw)
        bindFamily(family, version)
        return family.getString("id")
    }

    private fun bindFamily(family: JSONObject, version: Long) {
        val id = family.getString("id")
        require(UUID.fromString(id).toString() == id) { "家庭 ID 无效" }
        mutate(version) { state ->
            val user = state.getString("user")
            require(!state.has("boundUser") || state.getString("boundUser") == user) { "本机账本已绑定其他账号，请使用独立应用数据" }
            require(!state.has("familyId") || state.getString("familyId") == id) { "本机账本已绑定其他家庭，请先备份并使用独立应用数据" }
            state.put("boundUser", user).put("familyId", id).put("familyName", family.getString("name"))
            state.remove("pendingUser")
            state.remove("pendingAction")
        }
    }

    private fun markFamilyAttempt(version: Long) = mutate(version) {
        // A timed-out create/join can still commit remotely. Keep an account lock until recovered.
        it.put("pendingUser", it.getString("user")).put("pendingAction", true)
    }

    private fun acceptSession(response: JSONObject, version: Long) {
        val user = response.getJSONObject("user")
        val id = user.getString("id")
        require(UUID.fromString(id).toString() == id)
        val access = response.getString("access_token")
        val refresh = response.getString("refresh_token")
        require(access.isNotBlank() && refresh.isNotBlank()) { "登录响应缺少 token" }
        mutate(version) { state ->
            require(!state.has("boundUser") || state.getString("boundUser") == id) { "本机账本已绑定其他账号，登录被拒绝；请先备份并使用独立应用数据" }
            require(!state.has("pendingUser") || state.getString("pendingUser") == id) { "正在确认原账号的家庭归属，请先使用原账号恢复；不能切换账号" }
            require(!state.has("user") || state.getString("user") == id) { "请先退出当前账号" }
            state.put("user", id).put("email", user.optString("email"))
                .put("access", access).put("refresh", refresh)
                .put("expires", System.currentTimeMillis() + response.optLong("expires_in", 3600).coerceIn(1, 86400) * 1000)
            if (!state.has("boundUser")) state.put("pendingUser", id)
        }
    }

    private fun token(version: Long, forceRefresh: Boolean = false): String {
        val session = state(version)
        require(session.has("access")) { "请先登录云账号" }
        if (forceRefresh || session.optLong("expires") <= System.currentTimeMillis() + 60000) {
            val updated = json(request("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", session.getString("refresh")), null, version))
            acceptSession(updated, version)
        }
        return state(version).getString("access")
    }

    private fun rpc(name: String, body: JSONObject, version: Long): String = authorized("POST", "/rest/v1/rpc/$name", body, version)

    private fun authorized(method: String, path: String, body: JSONObject?, version: Long): String = try {
        request(method, path, body, token(version), version)
    } catch (e: HttpFailure) {
        if (e.status != 401) throw e
        request(method, path, body, token(version, forceRefresh = true), version)
    }

    private class HttpFailure(val status: Int, message: String) : IllegalStateException(message)

    private fun request(method: String, path: String, body: JSONObject?, bearer: String?, version: Long): String {
        val pair = synchronized(stateGuard) {
            checkEpoch(version)
            (config.getString("url", null) ?: error("请先配置 Supabase URL")) to
                (config.getString("anonKey", null) ?: error("请先配置公开 key"))
        }
        val base = officialUrl(pair.first)
        require(path.startsWith("/") && !path.startsWith("//"))
        syncMetrics?.takeIf { it.version == version }?.let { it.requests++ }
        val connection = URI(base + path).toURL().openConnection() as HttpURLConnection
        val deadline = System.nanoTime() + 90_000_000_000L
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = if (path.startsWith("/functions/")) 60000 else 20000
            connection.requestMethod = method
            connection.setRequestProperty("apikey", pair.second)
            connection.setRequestProperty("Accept", "application/json")
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= 6 * 1024 * 1024) { "请求数据过大" }
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            require(code !in 300..399) { "服务器重定向已拒绝，凭据未转发" }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    require(System.nanoTime() < deadline) { "云端响应超时，请稍后重试" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_RESPONSE) { "云端响应超过大小上限" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            synchronized(stateGuard) { checkEpoch(version) }
            if (code !in 200..299) {
                // Do not expose raw server errors, which may echo account/ledger content.
                throw HttpFailure(code, CloudErrors.message(code, path, String(bytes, Charsets.UTF_8)))
            }
            return String(bytes, Charsets.UTF_8)
        } finally { connection.disconnect() }
    }

    private fun officialUrl(value: String): String {
        val uri = try { URI(value.trim()) } catch (_: Exception) { error("Supabase URL 无效") }
        require(uri.scheme == "https" && uri.host != null && Regex("[a-z0-9-]+\\.supabase\\.co").matches(uri.host)
            && uri.rawUserInfo == null && uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null
            && uri.path in listOf("", "/")) { "仅支持 https://项目标识.supabase.co 官方项目根地址" }
        return "https://${uri.host}"
    }

    private fun json(text: String): JSONObject = JSONObject(text)
    private fun checkEpoch(version: Long) { check(epoch == version) { "云账号状态已改变，请重新操作" } }
    private fun state(version: Long): JSONObject = synchronized(stateGuard) { checkEpoch(version); readState() }
    private fun mutate(version: Long, block: (JSONObject) -> Unit) = synchronized(stateGuard) {
        checkEpoch(version)
        val state = readState()
        block(state)
        writeState(state)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build())
        }.generateKey()
    }

    private fun readState(): JSONObject {
        val encrypted = secrets.getString("state", null) ?: return JSONObject()
        try {
            val blob = Base64.decode(encrypted, Base64.NO_WRAP)
            require(blob.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            cipher.updateAAD(KEY_ALIAS.toByteArray(Charsets.UTF_8))
            return JSONObject(String(cipher.doFinal(blob.copyOfRange(12, blob.size)), Charsets.UTF_8))
        } catch (_: Exception) {
            error("本机云凭据或家庭绑定无法解密；为防误传已停止云功能，请先备份并使用独立应用数据")
        }
    }

    private fun writeState(state: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(KEY_ALIAS.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.iv + cipher.doFinal(state.toString().toByteArray(Charsets.UTF_8))
        check(secrets.edit().putString("state", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "无法安全保存云状态" }
    }

    private fun index(id: String, version: Long): SyncIndex? = synchronized(stateGuard) {
        checkEpoch(version)
        config.getString("index:$id", null)?.let {
            val parts = it.split(':')
            require(parts.size == 2 && Regex("[0-9a-f]{64}").matches(parts[1])) { "同步索引损坏，请停止同步" }
            SyncIndex(parts[0].toLong().also { revision -> require(revision > 0) }, parts[1])
        }
    }

    private fun indexes(version: Long): Map<String, SyncIndex> = synchronized(stateGuard) {
        checkEpoch(version)
        config.all.keys.filter { it.startsWith("index:") }.associate { key ->
            val id = key.removePrefix("index:")
            require(UUID.fromString(id).toString() == id) { "同步索引 ID 损坏" }
            id to requireNotNull(index(id, version))
        }
    }

    private fun remember(id: String, revision: Long, hash: String, version: Long) =
        rememberBatch(mapOf(id to SyncIndex(revision, hash)), version)

    private fun rememberBatch(values: Map<String, SyncIndex>, version: Long) = synchronized(stateGuard) {
        checkEpoch(version)
        val editor = config.edit()
        var changed = false
        values.forEach { (id, index) ->
            val value = "${index.revision}:${index.hash}"
            if (config.getString("index:$id", null) != value) {
                editor.putString("index:$id", value)
                changed = true
            }
        }
        if (changed) check(editor.commit()) { "同步索引保存失败，请重新同步" }
        Unit
    }

    private fun hash(entry: LedgerEntry): String {
        fun canonical(value: Any?): String = when (value) {
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            null, JSONObject.NULL -> "null"
            else -> value.toString()
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical(JSONObject(BackupCodec.encode(listOf(entry)))).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
