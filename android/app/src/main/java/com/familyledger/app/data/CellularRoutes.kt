package com.familyledger.app.data

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** Only the anonymous probe can fail over. The caller's operation is dispatched once. */
internal class CellularRoutes<N : Any>(private val nowMillis: () -> Long) {
    private data class Choice<N>(val network: N, val direct: Boolean, val expiresAt: Long)

    private val cached = AtomicReference<Choice<N>?>(null)

    private fun current(network: N): Choice<N>? =
        cached.get()?.takeIf { it.network == network && nowMillis() < it.expiresAt }

    fun cachedDirect(network: N): Boolean? = current(network)?.direct

    @Synchronized
    private fun select(network: N, probe: (N, Boolean) -> Unit): Choice<N> {
        current(network)?.let { return it }
        fun reachable(direct: Boolean): Boolean = try {
            probe(network, direct)
            true
        } catch (_: IOException) {
            false
        }
        val direct = when {
            reachable(true) -> true
            reachable(false) -> false
            else -> {
                // A slow cellular network can outlast both short checks. Keep the normal request timeout.
                cached.set(null)
                return Choice(network, true, nowMillis())
            }
        }
        return Choice(network, direct, nowMillis() + 5 * 60_000L).also { cached.set(it) }
    }

    fun <T> request(currentCellular: () -> N?, probe: (N, Boolean) -> Unit, send: (Boolean) -> T): T {
        var network = currentCellular()
        // A SIM or VPN can change during a handshake. Check the new default before sending.
        repeat(2) {
            val target = network ?: return send(false)
            val choice = current(target) ?: select(target, probe)
            network = currentCellular()
            if (network == target) {
                try {
                    return send(choice.direct)
                } catch (failure: IOException) {
                    cached.compareAndSet(choice, null)
                    throw failure
                }
            }
        }
        return send(false)
    }
}
