package com.familyledger.app.data

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CloudReadRecoveryTest {
    private val family = "/rest/v1/rpc/get_my_family"
    private val abort = SocketException("Software caused connection abort")

    private suspend fun <T> recoverCloudRead(method: String, path: String,
        awaitNetwork: suspend () -> Boolean, send: () -> T): T =
        authorizedCloudRequest(method, path, { "existing" }, awaitNetwork) { send() }

    @Test fun aHandoverRetriesTheInterruptedReadOnTheNewDefaultOnce() = runBlocking {
        for ((method, path) in listOf(
            "POST" to family, "POST" to "/rest/v1/rpc/get_ledger_manifest",
            "POST" to "/rest/v1/rpc/get_family_member_avatars",
            "GET" to "/rest/v1/ledger_entries?select=id,payload,revision"
        )) {
            var network = "cellular"
            val sentOn = mutableListOf<String>()
            val reply = recoverCloudRead(method, path, { network = "wifi"; true }) {
                sentOn += network
                if (sentOn.size == 1) throw abort
                "response"
            }
            assertEquals("response", reply)
            assertEquals(listOf("cellular", "wifi"), sentOn)
        }
    }

    @Test fun successfulReadsAndOperationsWithSideEffectsAreNeverReplayed() = runBlocking {
        assertEquals(42, recoverCloudRead("POST", family, { error("unexpected wait") }) { 42 })
        for ((method, path) in listOf(
            "POST" to "/rest/v1/rpc/put_ledger_entries", "POST" to "/rest/v1/rpc/put_ledger_entry",
            "POST" to "/rest/v1/rpc/set_my_member_avatar", "POST" to "/rest/v1/rpc/create_invite",
            "POST" to "/functions/v1/ledger-ai", "POST" to "/auth/v1/token?grant_type=refresh_token",
            "POST" to "/auth/v1/recover", "POST" to "/rest/v1/rpc/get_my_family?other=value",
            "PATCH" to "/rest/v1/ledger_entries", "GET" to "/rest/v1/other"
        )) {
            var calls = 0
            val failure = runCatching {
                recoverCloudRead(method, path, { error("unexpected wait") }) { calls++; throw abort }
            }.exceptionOrNull()
            assertSame(abort, failure)
            assertEquals(1, calls)
        }
    }

    @Test fun repeatedDisconnectGivesTheSecondFailureWithoutAThirdAttempt() = runBlocking {
        val eof = EOFException()
        var calls = 0
        val result = runCatching {
            recoverCloudRead("POST", family, { true }) { calls++; throw if (calls == 1) abort else eof }
        }
        assertEquals(2, calls)
        assertSame(eof, result.exceptionOrNull())
    }

    @Test fun anHttp401DoesNotGrantAnotherTransportRetry() = runBlocking {
        val tokens = mutableListOf<Boolean>()
        val attempts = mutableListOf<String>()
        var waits = 0
        val failure = runCatching {
            authorizedCloudRequest("POST", family, { refresh ->
                tokens += refresh
                if (refresh) "fresh" else "old"
            }, { waits++; true }) { bearer ->
                attempts += bearer
                when (attempts.size) {
                    1, 3 -> throw abort
                    2 -> throw CloudHttpFailure(401, "expired")
                    else -> "unexpected fourth attempt"
                }
            }
        }.exceptionOrNull()
        assertSame(abort, failure)
        assertEquals(listOf(false, true), tokens)
        assertEquals(listOf("old", "old", "fresh"), attempts)
        assertEquals(1, waits)
    }

    @Test fun interruptedTokenRefreshIsNeverReplayedByARead() = runBlocking {
        var refreshes = 0
        var sends = 0
        val error = runCatching {
            authorizedCloudRequest("POST", family, { force ->
                if (force) { refreshes++; throw abort }
                "old"
            }, { error("unexpected recovery for token rotation") }) {
                sends++
                throw CloudHttpFailure(401, "expired")
            }
        }.exceptionOrNull()
        assertSame(abort, error)
        assertEquals(1, refreshes)
        assertEquals(1, sends)
    }

    @Test fun aTruncatedHttpErrorBodyPreservesTheRejectionAndDoesNotTriggerAReadRetry() = runBlocking {
        for (status in listOf(403, 429)) {
            val failure = runCatching {
                recoverCloudRead("POST", family, { error("do not retry an HTTP rejection") }) {
                    val bytes = readCloudResponseBody(status) { throw EOFException() }
                    throw CloudHttpFailure(status, CloudErrors.message(status, family, bytes.decodeToString()))
                }
            }.exceptionOrNull()
            assertEquals(status, (failure as CloudHttpFailure).status)
        }
        val lost = EOFException()
        assertSame(lost, runCatching { readCloudResponseBody(200) { throw lost } }.exceptionOrNull())
    }

    @Test fun cancellationIsCheckedBeforeTokenWorkAndAfterWaitingForTheNetwork() = runBlocking {
        val events = mutableListOf<String>()
        launch {
            currentCoroutineContext().cancel()
            authorizedCloudRequest("POST", family, { events += "unexpected token"; "old" },
                { true }) { events += "unexpected send" }
        }.join()
        launch {
            val context = currentCoroutineContext()
            authorizedCloudRequest("POST", family, { events += "token"; "old" }, {
                events += "wait"
                context.cancel()
                true
            }) { events += "read"; throw abort }
        }.join()
        assertEquals(listOf("token", "read", "wait"), events)
    }

    @Test fun aMissingNetworkCancellationAndNonTransportErrorsStopTheRead() = runBlocking {
        var sends = 0
        assertSame(abort, runCatching {
            recoverCloudRead("POST", family, { false }) { sends++; throw abort }
        }.exceptionOrNull())
        val cancelled = CancellationException("cancel")
        assertSame(cancelled, runCatching {
            recoverCloudRead("POST", family, { throw cancelled }) { sends++; throw abort }
        }.exceptionOrNull())
        assertEquals(2, sends)
        val trust = SSLHandshakeException("handshake failed").apply { initCause(CertificateException("untrusted")) }
        for (failure in listOf(trust, IOException("unrelated"), SocketTimeoutException(), IllegalStateException("HTTP 403"))) {
            assertSame(failure, runCatching {
                recoverCloudRead("POST", family, { error("must not retry") }) { throw failure }
            }.exceptionOrNull())
        }
    }

    @Test fun settlingRequiresTheSameValidatedNetworkTwiceAndIsBounded() = runBlocking {
        val samples = listOf<Int?>(1, null, 2, 3, 3).iterator()
        var waits = 0
        assertTrue(awaitSteadyNetwork({ samples.next() }, { waits++ }))
        assertEquals(4, waits)
        waits = 0
        assertFalse(awaitSteadyNetwork<Int>({ null }, { waits++ }))
        assertEquals(8, waits)
    }
}
