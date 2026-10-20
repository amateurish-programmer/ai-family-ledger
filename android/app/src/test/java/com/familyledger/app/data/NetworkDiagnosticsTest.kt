package com.familyledger.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

class NetworkDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val sentinel = "PRIVATE_token_email@example.com_family_192.0.2.7"
    private val network = DiagnosticNetworkSnapshot(
        available = true, transports = listOf("wifi"), validated = true,
        captivePortal = false, metered = false, vpn = false,
        proxyConfigured = true, privateDnsMode = "active"
    )

    @Test fun actualRequestMetadataDropsQueriesAndUnknownPathSegments() {
        val record = DiagnosticRequestRecord.create(
            path = "/rest/v1/ledger_entries?select=*&family_id=eq.$sentinel",
            route = DiagnosticRoute.PAGES, stage = DiagnosticStage.READ_HEADERS,
            httpStatus = null, failure = IOException(sentinel), elapsedMillis = 12, network = network
        )
        val json = record.toJson().toString()
        assertEquals("ledger_entries", record.category)
        assertFalse(json.contains(sentinel))
        assertFalse(json.contains("family_id"))
        val unknown = DiagnosticRequestRecord.create("/rest/v1/rpc/$sentinel", DiagnosticRoute.SUPABASE,
            DiagnosticStage.SEND, null, null, 1, network)
        assertEquals("other", unknown.category)
        assertFalse(unknown.toJson().toString().contains(sentinel))
        assertEquals("auth_refresh", diagnosticRequestCategory("/auth/v1/token?grant_type=refresh_token"))
        assertEquals("auth_password", diagnosticRequestCategory("/auth/v1/token?grant_type=password"))
        assertEquals("auth_token", diagnosticRequestCategory("/auth/v1/token?grant_type=$sentinel"))
    }

    @Test fun snapshotRouteUsesTheExistingCellularWifiVpnPolicy() {
        val cases = listOf(
            DiagnosticNetworkSnapshot(true, listOf("cellular"), vpn = false) to "supabase",
            DiagnosticNetworkSnapshot(true, listOf("wifi"), vpn = false) to "pages",
            DiagnosticNetworkSnapshot(true, listOf("cellular", "vpn"), vpn = true) to "pages",
            DiagnosticNetworkSnapshot(true, listOf("cellular", "wifi"), vpn = false) to "pages",
            DiagnosticNetworkSnapshot(false) to "pages"
        )
        for ((snapshot, route) in cases) assertEquals(route, snapshot.toJson().getString("selectedRoute"))
    }

    @Test fun recentRequestsKeepOnlyNewestTwentyAndSurviveAStoreReopen() {
        val file = temporary.newFile("recent.json")
        val store = DiagnosticRequestStore(file)
        repeat(25) { index -> store.append(record(index.toLong())) }
        val reloaded = DiagnosticRequestStore(file).read()
        assertEquals(20, reloaded.size)
        assertEquals(5L, reloaded.first().elapsedMillis)
        assertEquals(24L, reloaded.last().elapsedMillis)
        assertFalse(file.readText().contains(sentinel))
    }

    @Test fun corruptOrUnwritableHistoryNeverEscapesIntoBusinessRequests() {
        val corrupt = temporary.newFile("corrupt.json").apply { writeText("{broken $sentinel") }
        val store = DiagnosticRequestStore(corrupt)
        assertTrue(store.read().isEmpty())
        store.append(record(1))
        assertEquals(1, store.read().size)
        val blocked = temporary.newFolder("directory-in-place-of-file")
        DiagnosticRequestStore(blocked).append(record(2))
        assertTrue(DiagnosticRequestStore(blocked).read().isEmpty())
    }

    @Test fun concurrentStoreInstancesDoNotLoseRecentRequests() {
        val file = temporary.newFile("concurrent.json")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val tasks = (0 until 12).map { index -> executor.submit {
                DiagnosticRequestStore(file).append(record(index.toLong()))
            } }
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals((0L..11L).toSet(), DiagnosticRequestStore(file).read().map { it.elapsedMillis }.toSet())
        } finally { executor.shutdownNow() }
    }

    @Test fun oversizedOrUnknownSchemaHistoryIsDiscarded() {
        val file = temporary.newFile("oversized.json")
        file.writeText(" ".repeat(140_000))
        assertTrue(DiagnosticRequestStore(file).read().isEmpty())
        file.writeText(JSONObject().put("schema", 99).put("requests", org.json.JSONArray().put(record(1).toJson())).toString())
        assertTrue(DiagnosticRequestStore(file).read().isEmpty())
    }

    @Test fun persistedUnknownFieldsAndTamperedLabelsDoNotReachExports() {
        val file = temporary.newFile("tampered.json")
        val row = record(1).toJson().put("token", sentinel).put("category", sentinel)
            .put("failure", sentinel).put("network", JSONObject().put("ssid", sentinel))
        file.writeText(JSONObject().put("schema", 1).put("requests", org.json.JSONArray().put(row)).toString())
        val reloaded = DiagnosticRequestStore(file).read()
        val report = buildNetworkDiagnosticReport(environment(), network, reloaded, emptyList())
        assertFalse(report.text.contains(sentinel))
        assertFalse(report.text.contains("ssid"))
    }

    @Test fun ai401OnlyMeansTheExpectedAuthenticationGateWasReached() {
        assertEquals("expected_auth_gate", diagnosticHttpOutcome(DiagnosticProbeKind.AI_AUTH_GATE, 401))
        assertEquals("http_server_error", diagnosticHttpOutcome(DiagnosticProbeKind.AI_AUTH_GATE, 503))
        assertEquals("http_client_error", diagnosticHttpOutcome(DiagnosticProbeKind.UPDATE_MANIFEST, 401))
        assertEquals("unexpected_auth_acceptance", diagnosticHttpOutcome(DiagnosticProbeKind.AI_AUTH_GATE, 200))
        assertEquals("reachable", diagnosticHttpOutcome(DiagnosticProbeKind.UPDATE_MANIFEST, 200))
    }

    @Test fun failureClassificationWalksCausesAndNeverExportsTheirMessages() {
        val cases = listOf(
            SocketException("Connection reset $sentinel") to "reset",
            UnknownHostException(sentinel) to "dns",
            SocketTimeoutException(sentinel) to "timeout",
            SSLHandshakeException(sentinel) to "tls",
            IOException(sentinel) to "io"
        )
        for ((failure, expected) in cases) {
            val wrapped = IllegalStateException(sentinel, RuntimeException(sentinel, failure))
            assertEquals(expected, diagnosticFailure(wrapped))
            assertFalse(record(1, wrapped).toJson().toString().contains(sentinel))
        }
    }

    @Test fun fixedProbePlanNeverSendsUserCredentialsOrLedgerContent() {
        val plan = diagnosticProbePlan()
        assertEquals(6, plan.size)
        assertEquals(setOf("pages", "supabase"), plan.map { it.target.label }.toSet())
        val dns = plan.filter { it.kind == DiagnosticProbeKind.DNS }
        assertEquals(setOf("family-ledger-gateway.pages.dev", "xdgeybztysuvvwagqkvb.supabase.co"), dns.map { it.url }.toSet())
        assertTrue(dns.all { it.headers.isEmpty() && it.body == null })
        for (target in DiagnosticTarget.entries) {
            assertEquals(DiagnosticProbeKind.entries.toSet(), plan.filter { it.target == target }.map { it.kind }.toSet())
        }
        val https = plan.filter { it.kind != DiagnosticProbeKind.DNS }
        assertEquals(setOf(
            "https://family-ledger-gateway.pages.dev/updates/latest.json",
            "https://xdgeybztysuvvwagqkvb.supabase.co/storage/v1/object/public/app-updates/latest.json",
            "https://family-ledger-gateway.pages.dev/functions/v1/ledger-ai",
            "https://xdgeybztysuvvwagqkvb.supabase.co/functions/v1/ledger-ai"
        ), https.map { it.url }.toSet())
        for (request in https) {
            assertFalse(request.headers.keys.any { it.equals("Authorization", ignoreCase = true) })
            assertTrue(request.headers.keys.all { it in setOf("Accept", "Content-Type", "apikey") })
            if (request.kind == DiagnosticProbeKind.AI_AUTH_GATE) {
                assertEquals("POST", request.method)
                assertEquals("{}", request.body)
                assertEquals(CloudEndpoint.PUBLIC_KEY, request.headers["apikey"])
            } else {
                assertEquals("GET", request.method)
                assertNull(request.body)
            }
        }
    }

    @Test fun offlineAndPermissionDeniedSnapshotsRemainExportable() {
        val offline = DiagnosticNetworkSnapshot(available = false)
        val denied = captureDiagnosticNetwork { throw SecurityException(sentinel) }
        for (snapshot in listOf(offline, denied)) {
            val report = buildNetworkDiagnosticReport(environment(), snapshot, emptyList(), emptyList())
            val json = JSONObject(report.text)
            assertEquals(1, json.getInt("schema"))
            assertTrue(json.getString("generatedAt").endsWith("Z"))
            assertEquals(0, json.getJSONArray("probes").length())
            assertFalse(report.text.contains(sentinel))
            assertTrue(report.summary.contains("连通性检查"))
            assertTrue(report.summary.contains("不验证账号和AI结果"))
        }
        assertFalse(denied.available)
    }

    @Test fun dnsReportContainsOnlyAddressFamilyCounts() {
        val result = DiagnosticProbeResult(DiagnosticTarget.PAGES, DiagnosticProbeKind.DNS,
            "complete", "reachable", 7, ipv4Count = 2, ipv6Count = 1)
        val report = buildNetworkDiagnosticReport(environment(), network, emptyList(), listOf(result))
        val dns = JSONObject(report.text).getJSONArray("probes").getJSONObject(0)
        assertEquals(2, dns.getInt("ipv4Count"))
        assertEquals(1, dns.getInt("ipv6Count"))
        assertFalse(dns.has("addresses"))
    }

    @Test fun reportRevalidatesAllFreeTextDiagnosticLabels() {
        val untrustedNetwork = network.copy(transports = listOf(sentinel), privateDnsMode = sentinel)
        val probe = DiagnosticProbeResult(DiagnosticTarget.PAGES, DiagnosticProbeKind.DNS,
            sentinel, sentinel, 1)
        val report = buildNetworkDiagnosticReport(environment(), untrustedNetwork, emptyList(), listOf(probe))
        assertFalse(report.text.contains(sentinel))
    }

    @Test fun observerFailureCannotReplaceABusinessResultOrOriginalFailure() {
        val result = try { "business result" } finally {
            observeNetworkDiagnostic { throw IOException(sentinel) }
        }
        assertEquals("business result", result)
        val original = SocketException("Connection reset $sentinel")
        try {
            try { throw original } finally {
                observeNetworkDiagnostic { throw AssertionError(sentinel) }
            }
        } catch (failure: Throwable) { assertSame(original, failure) }
    }

    @Test fun cancellationBeforeConnectionPublicationPreventsNetworkWork() {
        val cancellation = DiagnosticCancellation()
        cancellation.cancel()
        var started = false
        if (cancellation.registerClose { }) started = true
        assertFalse(started)
        assertThrows(CancellationException::class.java) { cancellation.checkActive() }
    }

    @Test fun cancellationAfterConnectionPublicationReturnsItsCloseAction() {
        val cancellation = DiagnosticCancellation()
        var closed = false
        assertTrue(cancellation.registerClose { closed = true })
        val close = cancellation.cancel()
        assertNotNull(close)
        close?.invoke()
        assertTrue(closed)
        assertNull(cancellation.cancel())
    }

    @Test fun cancelledProbeSequenceStopsBeforeTheNextTarget() = runBlocking {
        var calls = 0
        try {
            executeDiagnosticProbes(probe = { request ->
                calls++
                throw CancellationException("cancel $sentinel")
            })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(1, calls)
    }

    @Test fun abandoningABlockedResolverDoesNotWaitForItsWorkerToFinish() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        var cancelled = false
        try {
            val started = System.nanoTime()
            val value = withTimeoutOrNull(80) {
                awaitDiagnosticBlocking(executor, onCancel = { cancelled = true }) {
                    // Resolver APIs may ignore interruption. The caller must still stop waiting.
                    while (release.count > 0) {
                        try { release.await(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
                    }
                    "late result"
                }
            }
            assertNull(value)
            assertTrue(cancelled)
            assertTrue("Cancelled wait must return promptly", (System.nanoTime() - started) / 1_000_000 < 2000)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun record(elapsed: Long, failure: Throwable? = null) = DiagnosticRequestRecord.create(
        "/rest/v1/ledger_entries?email=$sentinel", DiagnosticRoute.PAGES,
        DiagnosticStage.READ_BODY, 200, failure, elapsed, network
    )

    private fun environment() = DiagnosticEnvironment("Honor", "Android 15", 35, "1.9.7", 29)
}
