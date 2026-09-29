package dev.mela.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class NetworkStatus(val online: Boolean, val metered: Boolean)

fun networkStatus(context: Context) = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    fun report() {
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        trySend(NetworkStatus(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true))
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
