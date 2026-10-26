package me.lvca.alba.app.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class NetworkStatus(val online: Boolean, val metered: Boolean, val roaming: Boolean = false)

/** The default network's status now. No network, or no capabilities, counts as offline and metered. */
internal fun ConnectivityManager.currentNetworkStatus(): NetworkStatus {
    val capabilities = getNetworkCapabilities(activeNetwork)
    return NetworkStatus(
        online = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        metered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true,
        roaming = capabilities != null && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
    )
}

fun networkStatus(context: Context) = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    fun report() {
        trySend(manager.currentNetworkStatus())
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = report()
        override fun onLost(network: Network) = report()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = report()
    }
    manager.registerDefaultNetworkCallback(callback)
    report()
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
