package me.lvca.alba.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import me.lvca.alba.engine.model.AutomaticBackupPolicy

class AndroidAutomaticBackupPolicy(context: Context) : AutomaticBackupPolicy {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun isUnmetered(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
