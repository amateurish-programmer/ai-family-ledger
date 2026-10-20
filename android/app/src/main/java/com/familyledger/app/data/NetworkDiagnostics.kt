package com.familyledger.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** Offline snapshot plus an explicit, fixed and unauthenticated connectivity check. */
class NetworkDiagnostics(context: Context) {
    private val appContext = context.applicationContext

    fun snapshot(): NetworkDiagnosticReport = report(emptyList())

    suspend fun run(onProgress: (String) -> Unit = {}): NetworkDiagnosticReport = withContext(Dispatchers.IO) {
        // Per-probe waits total at most 44 seconds. No account state or ledger is ever opened here.
        report(executeDiagnosticProbes(::probe, onProgress))
    }

    private fun report(probes: List<DiagnosticProbeResult>): NetworkDiagnosticReport {
        val environment = runCatching {
            @Suppress("DEPRECATION")
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            DiagnosticEnvironment("${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT, info.versionName ?: "unknown", code)
        }.getOrElse { DiagnosticEnvironment("unknown", "unknown", Build.VERSION.SDK_INT, "unknown", 0) }
        return buildNetworkDiagnosticReport(environment, readDiagnosticNetwork(appContext),
            CloudRequestDiagnosticTrace.history(appContext), probes)
    }

    private suspend fun probe(request: DiagnosticProbeRequest): DiagnosticProbeResult {
        val started = System.nanoTime()
        val stage = AtomicReference(if (request.kind == DiagnosticProbeKind.DNS) "dns" else "open")
        val cancellation = DiagnosticCancellation()
        val tlsMillis = AtomicReference<Long?>()
        val responseMillis = AtomicReference<Long?>()
        val status = AtomicReference<Int?>()
        fun result(outcome: String, failure: String? = null, ipv4: Int? = null, ipv6: Int? = null) =
            DiagnosticProbeResult(request.target, request.kind, stage.get(), outcome,
                elapsed(started), status.get(), failure, ipv4, ipv6, tlsMillis.get(), responseMillis.get())
        val timeout = if (request.kind == DiagnosticProbeKind.DNS) 4_000L else 9_000L
        fun checkActive() {
            cancellation.checkActive()
            if (elapsed(started) >= timeout) throw SocketTimeoutException("Diagnostic deadline")
        }
        return try {
            withTimeoutOrNull(timeout) {
                awaitDiagnosticBlocking(workers, onCancel = {
                    // Closing may touch platform I/O, so cancellation from UI never closes on its thread.
                    val close = cancellation.cancel()
                    if (close != null) closers.execute { runCatching(close) }
                }) {
                    checkActive()
                    if (request.kind == DiagnosticProbeKind.DNS) {
                        val addresses = InetAddress.getAllByName(request.target.host)
                        checkActive()
                        stage.set("complete")
                        result("reachable", ipv4 = addresses.count { it is Inet4Address }, ipv6 = addresses.count { it is Inet6Address })
                    } else {
                        val https = URL(request.url).openConnection() as HttpsURLConnection
                        try {
                            if (!cancellation.registerClose { https.disconnect() }) {
                                throw kotlinx.coroutines.CancellationException("Diagnostic cancelled")
                            }
                            checkActive()
                            https.instanceFollowRedirects = false
                            https.connectTimeout = 5_000
                            https.readTimeout = 5_000
                            https.requestMethod = request.method
                            request.headers.forEach { (key, value) -> https.setRequestProperty(key, value) }
                            val bytes = request.body?.toByteArray(Charsets.UTF_8)
                            if (bytes != null) {
                                https.doOutput = true
                                https.setFixedLengthStreamingMode(bytes.size)
                            }
                            stage.set("connect_tls")
                            val tlsStarted = System.nanoTime()
                            // Default SSLSocketFactory and HostnameVerifier remain untouched.
                            https.connect()
                            checkActive()
                            tlsMillis.set(elapsed(tlsStarted))
                            val responseStarted = System.nanoTime()
                            stage.set("send")
                            checkActive()
                            if (bytes != null) https.outputStream.use { it.write(bytes) }
                            checkActive()
                            stage.set("read_headers")
                            val code = https.responseCode
                            checkActive()
                            status.set(code)
                            responseMillis.set(elapsed(responseStarted))
                            if (code !in 300..399) {
                                stage.set("read_body")
                                val input = if (code in 200..299) https.inputStream else https.errorStream
                                input?.use {
                                    val buffer = ByteArray(4_096)
                                    var count = 0
                                    while (count <= 64 * 1024) {
                                        checkActive()
                                        val read = it.read(buffer)
                                        if (read < 0) break
                                        count += read
                                    }
                                    if (count > 64 * 1024) throw IOException("Diagnostic response size limit")
                                }
                            }
                            stage.set("complete")
                            result(diagnosticHttpOutcome(request.kind, code))
                        } finally {
                            https.disconnect()
                        }
                    }
                }
            } ?: result("failed", "timeout")
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: Exception) {
            result("failed", diagnosticFailure(failure))
        }
    }

    companion object {
        // Resolver calls may ignore interruption; bound workers and queued work instead of accumulating them.
        private val workers = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(4),
            { action -> Thread(action, "ledger-diagnostic").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
        private val closers = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
            { action -> Thread(action, "ledger-diagnostic-close").apply { isDaemon = true } }, ThreadPoolExecutor.DiscardPolicy())
        private fun elapsed(started: Long) = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)
    }
}

internal fun readDiagnosticNetwork(context: Context): DiagnosticNetworkSnapshot = captureDiagnosticNetwork {
    val manager = context.getSystemService(ConnectivityManager::class.java)
        ?: return@captureDiagnosticNetwork DiagnosticNetworkSnapshot(false, collection = "unavailable")
    val active = manager.activeNetwork
    val capabilities = manager.getNetworkCapabilities(active)
    val proxy = manager.defaultProxy != null
    val privateDns = when {
        Build.VERSION.SDK_INT < 28 -> "unsupported"
        active == null -> "unknown"
        else -> manager.getLinkProperties(active)?.let { if (it.isPrivateDnsActive) "active" else "inactive" } ?: "unknown"
    }
    if (capabilities == null) DiagnosticNetworkSnapshot(false, proxyConfigured = proxy, privateDnsMode = privateDns)
    else {
        val transports = buildList {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
            if (Build.VERSION.SDK_INT >= 31 && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB)) add("usb")
            if (isEmpty()) add("other")
        }
        DiagnosticNetworkSnapshot(true, transports,
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN), proxy, privateDns)
    }
}

/** No headers, bodies, credentials, response payloads or query values enter this observer. */
internal class CloudRequestDiagnosticTrace private constructor(
    private val context: Context, private val category: String, private val route: DiagnosticRoute,
    private val network: DiagnosticNetworkSnapshot
) {
    private val started = System.nanoTime()
    var stage = DiagnosticStage.OPEN
    var httpStatus: Int? = null
    var failure: Throwable? = null

    fun finish() = observeNetworkDiagnostic {
        store(context).append(DiagnosticRequestRecord.createCategory(category, route, stage, httpStatus, failure,
            (System.nanoTime() - started) / 1_000_000, network))
    }

    companion object {
        private fun store(context: Context) = DiagnosticRequestStore(File(context.cacheDir, "network-diagnostics/recent-requests.json"))
        fun start(context: Context, category: String, preferDirect: Boolean): CloudRequestDiagnosticTrace? {
            var trace: CloudRequestDiagnosticTrace? = null
            observeNetworkDiagnostic {
                trace = CloudRequestDiagnosticTrace(context, category,
                    if (preferDirect) DiagnosticRoute.SUPABASE else DiagnosticRoute.PAGES, readDiagnosticNetwork(context))
            }
            return trace
        }
        fun history(context: Context): List<DiagnosticRequestRecord> {
            var rows = emptyList<DiagnosticRequestRecord>()
            observeNetworkDiagnostic { rows = store(context).read() }
            return rows
        }
    }
}
