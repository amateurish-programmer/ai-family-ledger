package com.familyledger.app.data

import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

// These RPCs use POST on the wire but only read data in PostgreSQL.
private val readOnlyRpcs = setOf("/rest/v1/rpc/get_my_family", "/rest/v1/rpc/get_ledger_manifest",
    "/rest/v1/rpc/get_family_member_avatars")
private val disconnectKinds = setOf("abort", "reset", "eof", "connect")

internal fun isConnectionInterruption(failure: IOException): Boolean =
    diagnosticFailure(failure) in disconnectKinds

internal class CloudHttpFailure(val status: Int, message: String) : IllegalStateException(message)

internal suspend fun <T> authorizedCloudRequest(
    method: String, path: String, token: (Boolean) -> String, awaitNetwork: suspend () -> Boolean, send: (String) -> T
): T {
    val context = currentCoroutineContext()
    context.ensureActive()
    var bearer = token(false)
    var canRefreshToken = true
    var canRetryRead = (method == "POST" && path in readOnlyRpcs) ||
        (method == "GET" && path.substringBefore('?') == "/rest/v1/ledger_entries")
    while (true) {
        context.ensureActive()
        try {
            return send(bearer)
        } catch (failure: CloudHttpFailure) {
            if (failure.status != 401 || !canRefreshToken) throw failure
            canRefreshToken = false
            context.ensureActive()
            bearer = token(true)
        } catch (failure: IOException) {
            if (!canRetryRead || !isConnectionInterruption(failure)) throw failure
            canRetryRead = false
            context.ensureActive()
            if (!awaitNetwork()) throw failure
        }
    }
}

/** An HTTP rejection is already definitive even if the optional error body is truncated. */
internal inline fun readCloudResponseBody(status: Int, read: () -> ByteArray): ByteArray =
    try { read() } catch (failure: IOException) {
        if (status !in 400..599) throw failure
        byteArrayOf()
    }

/** The supplier returns only validated default networks. Never bind the business request to an old network. */
internal suspend fun <N : Any> awaitSteadyNetwork(
    current: () -> N?, pause: suspend () -> Unit = { delay(400) }
): Boolean {
    var previous = current()
    repeat(8) {
        pause()
        val next = current()
        if (next != null && next == previous) return true
        previous = next
    }
    return false
}
