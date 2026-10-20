package com.familyledger.app.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ExecutorService
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class NetworkDiagnosticReport(val generatedAt: String, val summary: String, val text: String)

internal enum class DiagnosticRoute(val label: String) { PAGES("pages"), SUPABASE("supabase") }
internal enum class DiagnosticStage(val label: String) {
    OPEN("open"), SEND("send"), READ_HEADERS("read_headers"), READ_BODY("read_body"), COMPLETE("complete")
}

internal data class DiagnosticNetworkSnapshot(
    val available: Boolean,
    val transports: List<String> = emptyList(),
    val validated: Boolean? = null,
    val captivePortal: Boolean? = null,
    val metered: Boolean? = null,
    val vpn: Boolean? = null,
    val proxyConfigured: Boolean? = null,
    val privateDnsMode: String = "unknown",
    val collection: String = "available"
) {
    fun toJson(): JSONObject = JSONObject().put("available", available)
        .put("selectedRoute", if (available && CloudEndpoint.prefersDirect("cellular" in transports,
            "wifi" in transports, vpn == true || "vpn" in transports)) "supabase" else "pages")
        .put("transports", JSONArray(transports.filter { it in TRANSPORTS }.distinct()))
        .put("validated", validated ?: JSONObject.NULL).put("captivePortal", captivePortal ?: JSONObject.NULL)
        .put("metered", metered ?: JSONObject.NULL).put("vpn", vpn ?: JSONObject.NULL)
        .put("proxyConfigured", proxyConfigured ?: JSONObject.NULL)
        .put("privateDnsMode", privateDnsMode.takeIf { it in DNS_MODES } ?: "unknown")
        .put("collection", collection.takeIf { it in COLLECTIONS } ?: "unavailable")

    companion object {
        private val TRANSPORTS = setOf("wifi", "cellular", "vpn", "ethernet", "bluetooth", "usb", "other")
        private val DNS_MODES = setOf("active", "inactive", "unsupported", "unknown")
        private val COLLECTIONS = setOf("available", "permission_denied", "unavailable")
        fun fromJson(json: JSONObject): DiagnosticNetworkSnapshot {
            fun boolean(name: String) = json.opt(name) as? Boolean
            val transports = json.optJSONArray("transports") ?: JSONArray()
            return DiagnosticNetworkSnapshot(json.optBoolean("available"),
                (0 until transports.length()).map { transports.optString(it) }.filter { it in TRANSPORTS },
                boolean("validated"), boolean("captivePortal"), boolean("metered"), boolean("vpn"),
                boolean("proxyConfigured"), json.optString("privateDnsMode").takeIf { it in DNS_MODES } ?: "unknown",
                json.optString("collection").takeIf { it in COLLECTIONS } ?: "unavailable")
        }
    }
}

internal fun captureDiagnosticNetwork(read: () -> DiagnosticNetworkSnapshot): DiagnosticNetworkSnapshot = try {
    read()
} catch (_: SecurityException) {
    DiagnosticNetworkSnapshot(false, collection = "permission_denied")
} catch (_: Exception) {
    DiagnosticNetworkSnapshot(false, collection = "unavailable")
}

internal data class DiagnosticEnvironment(
    val model: String, val androidVersion: String, val api: Int, val appVersion: String, val appVersionCode: Long
)

private val requestCategories = mapOf(
    "/auth/v1/signup" to "auth_signup", "/auth/v1/token" to "auth_token",
    "/auth/v1/recover" to "auth_recover", "/auth/v1/verify" to "auth_verify",
    "/auth/v1/user" to "auth_user", "/auth/v1/logout" to "auth_logout",
    "/rest/v1/ledger_entries" to "ledger_entries", "/functions/v1/ledger-ai" to "ledger_ai"
) + listOf("get_my_family", "get_ledger_manifest", "put_ledger_entries", "put_ledger_entry",
    "create_family", "join_family", "create_invite", "update_family_profile").associate { "/rest/v1/rpc/$it" to it }
private val requestCategoryLabels = requestCategories.values.toSet() + setOf("auth_refresh", "auth_password", "other")
private val failures = setOf("reset", "dns", "timeout", "tls", "io", "other")

/** Only exact public grant names are mapped; arbitrary query values never survive this function. */
internal fun diagnosticRequestCategory(path: String): String {
    val cleanPath = path.substringBefore('?')
    if (cleanPath == "/auth/v1/token") return when (path.substringAfter('?', "")) {
        "grant_type=refresh_token" -> "auth_refresh"
        "grant_type=password" -> "auth_password"
        else -> "auth_token"
    }
    return requestCategories[cleanPath] ?: "other"
}

/** Classify locally; the exception message, type name and stack are never serialized. */
internal fun diagnosticFailure(failure: Throwable): String {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val causes = generateSequence(failure) { it.cause }.takeWhile { seen.add(it) }.take(24).toList()
    return when {
        causes.any { it is SSLException } -> "tls"
        causes.any { it is UnknownHostException } -> "dns"
        causes.any { it is SocketTimeoutException || it is java.util.concurrent.TimeoutException } -> "timeout"
        causes.any { it is SocketException && it.message.orEmpty().contains("reset", ignoreCase = true) } -> "reset"
        causes.any { it is IOException } -> "io"
        else -> "other"
    }
}

internal class DiagnosticRequestRecord private constructor(
    val timeUtc: String, val category: String, val route: DiagnosticRoute, val stage: DiagnosticStage,
    val httpStatus: Int?, val failure: String?, val elapsedMillis: Long, val network: DiagnosticNetworkSnapshot
) {
    fun toJson(): JSONObject = JSONObject().put("timeUtc", Instant.parse(timeUtc).toString())
        .put("category", category.takeIf { it in requestCategoryLabels } ?: "other")
        .put("route", route.label).put("stage", stage.label)
        .put("httpStatus", httpStatus?.takeIf { it in 100..599 } ?: JSONObject.NULL)
        .put("failure", failure?.takeIf { it in failures } ?: JSONObject.NULL)
        .put("elapsedMillis", elapsedMillis.coerceIn(0, 86_400_000)).put("network", network.toJson())

    companion object {
        fun create(path: String, route: DiagnosticRoute, stage: DiagnosticStage, httpStatus: Int?,
            failure: Throwable?, elapsedMillis: Long, network: DiagnosticNetworkSnapshot): DiagnosticRequestRecord =
            createCategory(diagnosticRequestCategory(path), route, stage, httpStatus, failure, elapsedMillis, network)

        fun createCategory(category: String, route: DiagnosticRoute, stage: DiagnosticStage, httpStatus: Int?,
            failure: Throwable?, elapsedMillis: Long, network: DiagnosticNetworkSnapshot): DiagnosticRequestRecord =
            DiagnosticRequestRecord(Instant.now().toString(), category.takeIf { it in requestCategoryLabels } ?: "other",
                route, stage, httpStatus, failure?.let(::diagnosticFailure), elapsedMillis, network)

        fun fromJson(json: JSONObject): DiagnosticRequestRecord {
            val category = json.getString("category")
            require(category in requestCategoryLabels)
            val route = DiagnosticRoute.entries.single { it.label == json.getString("route") }
            val stage = DiagnosticStage.entries.single { it.label == json.getString("stage") }
            val failure = if (json.isNull("failure")) null else json.getString("failure").also { require(it in failures) }
            val status = if (json.isNull("httpStatus")) null else json.getInt("httpStatus").also { require(it in 100..599) }
            return DiagnosticRequestRecord(Instant.parse(json.getString("timeUtc")).toString(), category, route, stage,
                status, failure, json.getLong("elapsedMillis").coerceIn(0, 86_400_000),
                DiagnosticNetworkSnapshot.fromJson(json.getJSONObject("network")))
        }
    }
}

/** Independent small cache. A corrupt file or failed write must not affect accounting or cloud work. */
internal class DiagnosticRequestStore(private val file: File) {
    fun read(): List<DiagnosticRequestRecord> = synchronized(guard) { readLocked() }

    fun append(record: DiagnosticRequestRecord) {
        synchronized(guard) {
            try {
                val rows = (readLocked() + record).takeLast(20)
                file.parentFile?.mkdirs()
                val pending = File(file.parentFile, file.name + ".tmp")
                pending.writeText(JSONObject().put("schema", 1)
                    .put("requests", JSONArray().apply { rows.forEach { put(it.toJson()) } }).toString(), Charsets.UTF_8)
                try {
                    Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (_: Exception) { /* Diagnostics are deliberately fail-open. */ }
        }
    }

    private fun readLocked(): List<DiagnosticRequestRecord> = try {
        if (!file.isFile || file.length() > 128 * 1024) emptyList() else {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            require(root.getInt("schema") == 1)
            val rows = root.getJSONArray("requests")
            (maxOf(0, rows.length() - 20) until rows.length()).mapNotNull { index ->
                runCatching { DiagnosticRequestRecord.fromJson(rows.getJSONObject(index)) }.getOrNull()
            }
        }
    } catch (_: Exception) { emptyList() }

    companion object { private val guard = Any() }
}

internal enum class DiagnosticTarget(val label: String, val host: String) {
    PAGES("pages", "family-ledger-gateway.pages.dev"), SUPABASE("supabase", "xdgeybztysuvvwagqkvb.supabase.co")
}
internal enum class DiagnosticProbeKind(val label: String) {
    DNS("dns"), UPDATE_MANIFEST("update_manifest"), AI_AUTH_GATE("ai_auth_gate")
}
internal data class DiagnosticProbeRequest(
    val target: DiagnosticTarget, val kind: DiagnosticProbeKind, val url: String,
    val method: String, val headers: Map<String, String> = emptyMap(), val body: String? = null
)

/** There is intentionally no URL input in the public diagnostics API. */
internal fun diagnosticProbePlan(): List<DiagnosticProbeRequest> = DiagnosticTarget.entries.flatMap { target ->
    listOf(
        DiagnosticProbeRequest(target, DiagnosticProbeKind.DNS, target.host, "DNS"),
        DiagnosticProbeRequest(target, DiagnosticProbeKind.UPDATE_MANIFEST,
            AppUpdate.manifestUrl(target == DiagnosticTarget.SUPABASE), "GET", mapOf("Accept" to "application/json")),
        DiagnosticProbeRequest(target, DiagnosticProbeKind.AI_AUTH_GATE, "https://${target.host}/functions/v1/ledger-ai",
            "POST", mapOf("Accept" to "application/json", "Content-Type" to "application/json; charset=utf-8",
                "apikey" to CloudEndpoint.PUBLIC_KEY), "{}")
    )
}

internal fun diagnosticHttpOutcome(kind: DiagnosticProbeKind, status: Int): String = when {
    kind == DiagnosticProbeKind.AI_AUTH_GATE && status == 401 -> "expected_auth_gate"
    kind == DiagnosticProbeKind.AI_AUTH_GATE && status in 200..299 -> "unexpected_auth_acceptance"
    status in 200..299 -> "reachable"
    status in 300..399 -> "redirect_refused"
    status in 400..499 -> "http_client_error"
    status in 500..599 -> "http_server_error"
    else -> "invalid_http_status"
}

internal data class DiagnosticProbeResult(
    val target: DiagnosticTarget, val kind: DiagnosticProbeKind, val stage: String, val outcome: String,
    val elapsedMillis: Long, val httpStatus: Int? = null, val failure: String? = null,
    val ipv4Count: Int? = null, val ipv6Count: Int? = null,
    val connectTlsMillis: Long? = null, val responseMillis: Long? = null
) {
    fun toJson(): JSONObject = JSONObject().put("target", target.label).put("kind", kind.label)
        .put("stage", stage.takeIf { it in setOf("dns", "open", "connect_tls", "send", "read_headers", "read_body", "complete") } ?: "unknown")
        .put("outcome", outcome.takeIf { it in setOf("reachable", "expected_auth_gate", "unexpected_auth_acceptance",
            "redirect_refused", "http_client_error", "http_server_error", "invalid_http_status", "failed") } ?: "failed")
        .put("elapsedMillis", elapsedMillis.coerceIn(0, 60_000))
        .put("httpStatus", httpStatus?.takeIf { it in 100..599 } ?: JSONObject.NULL)
        .put("failure", failure?.takeIf { it in failures } ?: JSONObject.NULL)
        .put("ipv4Count", ipv4Count?.coerceIn(0, 1000) ?: JSONObject.NULL)
        .put("ipv6Count", ipv6Count?.coerceIn(0, 1000) ?: JSONObject.NULL)
        .put("connectTlsMillis", connectTlsMillis?.coerceIn(0, 60_000) ?: JSONObject.NULL)
        .put("responseMillis", responseMillis?.coerceIn(0, 60_000) ?: JSONObject.NULL)
}

internal fun buildNetworkDiagnosticReport(environment: DiagnosticEnvironment, network: DiagnosticNetworkSnapshot,
    recent: List<DiagnosticRequestRecord>, probes: List<DiagnosticProbeResult>): NetworkDiagnosticReport {
    val generatedAt = Instant.now().toString()
    val summary = "连通性检查，不验证账号和AI结果。" +
        if (probes.isEmpty()) "离线快照，未发起探测。" else "已完成 ${probes.size} 项检查；AI 入口的 401 仅表示鉴权门槛可达。"
    fun deviceText(value: String) = value.filterNot { it.isISOControl() }.take(100)
    val report = JSONObject().put("schema", 1).put("generatedAt", generatedAt).put("summary", summary)
        .put("device", JSONObject().put("model", deviceText(environment.model)).put("android", deviceText(environment.androidVersion))
            .put("api", environment.api).put("appVersion", deviceText(environment.appVersion)).put("appVersionCode", environment.appVersionCode))
        .put("network", network.toJson()).put("recentRequests", JSONArray().apply { recent.takeLast(20).forEach { put(it.toJson()) } })
        .put("probes", JSONArray().apply { probes.forEach { put(it.toJson()) } })
    return NetworkDiagnosticReport(generatedAt, summary, report.toString(2))
}

internal suspend fun executeDiagnosticProbes(
    probe: suspend (DiagnosticProbeRequest) -> DiagnosticProbeResult, onProgress: (String) -> Unit = {}
): List<DiagnosticProbeResult> = diagnosticProbePlan().map { request ->
    currentCoroutineContext().ensureActive()
    val target = if (request.target == DiagnosticTarget.PAGES) "Pages" else "原 Supabase"
    val kind = when (request.kind) {
        DiagnosticProbeKind.DNS -> "DNS"
        DiagnosticProbeKind.UPDATE_MANIFEST -> "更新清单"
        DiagnosticProbeKind.AI_AUTH_GATE -> "鉴权门槛"
    }
    onProgress("正在检查 $target · $kind")
    probe(request)
}

/** Cancellation abandons the wait even when a platform DNS resolver ignores interruption. */
internal suspend fun <T> awaitDiagnosticBlocking(executor: ExecutorService, onCancel: () -> Unit = {}, work: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        val task = try {
            executor.submit {
                try {
                    if (continuation.isActive) {
                        val value = work()
                        if (continuation.isActive) continuation.resume(value)
                    }
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        } catch (failure: Exception) {
            continuation.resumeWithException(failure)
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation {
            task.cancel(true)
            runCatching(onCancel)
        }
    }

/** This boundary is used only by the diagnostic observer, never around a business operation. */
internal inline fun observeNetworkDiagnostic(action: () -> Unit) {
    try { action() } catch (_: Throwable) { /* The original request always keeps its own result or failure. */ }
}

/** Registering and cancelling share one lock so cancellation cannot miss a newly published handle. */
internal class DiagnosticCancellation {
    private val guard = Any()
    private var cancelled = false
    private var close: (() -> Unit)? = null

    fun registerClose(action: () -> Unit): Boolean = synchronized(guard) {
        if (cancelled) false else { close = action; true }
    }

    fun cancel(): (() -> Unit)? = synchronized(guard) {
        cancelled = true
        close.also { close = null }
    }

    fun checkActive() = synchronized(guard) {
        if (cancelled) throw kotlinx.coroutines.CancellationException("Diagnostic cancelled")
    }
}
