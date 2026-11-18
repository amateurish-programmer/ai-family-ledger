package com.familyledger.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal object CloudNetwork {
    private val cellular = CellularRoutes<Network>(SystemClock::elapsedRealtime)

    fun <T> withRoute(context: Context, send: (Boolean) -> T): T {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return cellular.request({ currentCellular(manager) }, ::probe, send)
    }

    fun cachedDirect(network: Network): Boolean? = cellular.cachedDirect(network)

    private fun currentCellular(manager: ConnectivityManager?): Network? {
        val network = manager?.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        val pureCellular = CloudEndpoint.prefersDirect(
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        )
        return network.takeIf { pureCellular }
    }

    private fun probe(network: Network, direct: Boolean) {
        // Public update URL, system trust store, network DNS and proxy. No session or ledger data.
        val url = URL(AppUpdate.manifestUrl(preferDirect = direct))
        val connection = network.openConnection(url) as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.connect()
        } finally {
            connection.disconnect()
        }
    }
}
