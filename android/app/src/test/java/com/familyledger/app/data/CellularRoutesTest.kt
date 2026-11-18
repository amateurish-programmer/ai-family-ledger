package com.familyledger.app.data

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CellularRoutesTest {
    private var now = 0L
    private val routes = CellularRoutes<Int> { now }

    @Test fun mobileTlsResetSelectsPagesBeforeSendingTheFirstWrite() {
        val events = mutableListOf<String>()
        val result = routes.request({ 1 }, { _, direct ->
            events += if (direct) "probe direct" else "probe pages"
            if (direct) throw SocketException("Connection reset")
        }) { direct ->
            events += if (direct) "send direct" else "send pages"
            "receipt"
        }
        assertEquals("receipt", result)
        assertEquals(listOf("probe direct", "probe pages", "send pages"), events)
        assertEquals(false, routes.cachedDirect(1))
        assertFalse(routes.request({ 1 }, { _, _ -> error("unexpected second probe") }) { it })
    }

    @Test fun workingCellularUsesDirectAndRechecksAfterExpiryOrSimChange() {
        val probes = mutableListOf<Int>()
        val check: (Int, Boolean) -> Unit = { network, direct ->
            assertTrue(direct)
            probes += network
        }
        assertTrue(routes.request({ 1 }, check) { it })
        now = 299_999
        assertTrue(routes.request({ 1 }, check) { it })
        now++
        assertTrue(routes.request({ 1 }, check) { it })
        assertFalse(routes.request({ 2 }, { network, direct ->
            probes += network
            if (direct) throw SocketTimeoutException()
        }) { it })
        assertEquals(listOf(1, 1, 2, 2), probes)
    }

    @Test fun lostWriteResponseIsReturnedWithoutReplayAndNextRequestRechecks() {
        val lost = SocketException("reset after server received the write")
        val writes = mutableListOf<Boolean>()
        val thrown = assertThrows(SocketException::class.java) {
            routes.request({ 1 }, { _, _ -> }) { direct -> writes += direct; throw lost }
        }
        assertSame(lost, thrown)
        assertEquals(listOf(true), writes)
        assertNull(routes.cachedDirect(1))
        assertFalse(routes.request({ 1 }, { _, direct -> if (direct) throw SocketException("reset") }) { it })
    }

    @Test fun twoInconclusiveShortProbesKeepTheNormalDirectAttemptAndCacheNothing() {
        var sends = 0
        val probes = mutableListOf<Boolean>()
        assertTrue(routes.request({ 1 }, { _, direct ->
            probes += direct
            throw SocketTimeoutException("short probe timed out")
        }) {
            sends++
            it
        })
        assertEquals(1, sends)
        assertEquals(listOf(true, false), probes)
        assertNull(routes.cachedDirect(1))
    }

    @Test fun switchingToWifiDuringTheProbeUsesPagesAndVpnNeedsNoProbe() {
        var cellular: Int? = 1
        assertFalse(routes.request({ cellular }, { _, _ -> cellular = null }) { it })
        assertFalse(routes.request({ null }, { _, _ -> error("must not probe on Wi-Fi or VPN") }) { it })
    }

    @Test fun concurrentOperationsShareOneProbeAndSendEachRequestOnce() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val probes = AtomicInteger()
        val sends = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = List(2) {
                pool.submit<Boolean> {
                    routes.request({ 1 }, { _, _ ->
                        probes.incrementAndGet()
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }) { direct -> sends.incrementAndGet(); direct }
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            jobs.forEach { assertTrue(it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, probes.get())
            assertEquals(2, sends.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
