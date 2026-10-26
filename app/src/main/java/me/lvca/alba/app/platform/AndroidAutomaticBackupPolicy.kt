package me.lvca.alba.app.platform

import android.content.Context
import android.net.ConnectivityManager
import me.lvca.alba.engine.model.AutomaticBackupPolicy
import me.lvca.alba.engine.model.BackupNetwork
import me.lvca.alba.engine.model.BackupSettings

class AndroidAutomaticBackupPolicy(context: Context, private val preferences: BackupPreferences) : AutomaticBackupPolicy {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun isUnmetered(): Boolean = network() == BackupNetwork.UNMETERED

    override fun network(): BackupNetwork = connectivity.currentNetworkStatus().backupNetwork

    override fun settings(): BackupSettings = preferences.settings.value
}

/** The network as automatic backup sees it: roaming counts before metered, since it costs more. */
val NetworkStatus.backupNetwork: BackupNetwork
    get() = when {
        !online -> BackupNetwork.OFFLINE
        roaming -> BackupNetwork.ROAMING
        metered -> BackupNetwork.METERED
        else -> BackupNetwork.UNMETERED
    }
