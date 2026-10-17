package com.familyledger.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Select the route for each new request; never switch/retry an in-flight write. */
internal object CloudNetwork {
    fun prefersDirect(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return CloudEndpoint.prefersDirect(
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        )
    }
}
