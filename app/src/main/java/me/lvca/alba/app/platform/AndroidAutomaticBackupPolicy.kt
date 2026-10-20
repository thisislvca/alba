package me.lvca.alba.app.platform

import android.content.Context
import android.net.ConnectivityManager
import me.lvca.alba.engine.model.AutomaticBackupPolicy

class AndroidAutomaticBackupPolicy(context: Context) : AutomaticBackupPolicy {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun isUnmetered(): Boolean = connectivity.currentNetworkStatus().let { it.online && !it.metered }
}
