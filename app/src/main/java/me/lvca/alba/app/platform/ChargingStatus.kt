package me.lvca.alba.app.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether the phone is charging now, the way WorkManager's charging constraint decides it: BatteryManager's charging
 * state, which follows ACTION_CHARGING and ACTION_DISCHARGING rather than only whether a cable is in.
 */
internal fun isCharging(context: Context): Boolean =
    context.getSystemService(BatteryManager::class.java)?.isCharging == true

/** Whether the phone is charging, updated as the charger comes and goes. */
fun chargingStatus(context: Context): Flow<Boolean> = callbackFlow {
    val appContext = context.applicationContext
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            trySend(isCharging(appContext))
        }
    }
    val filter = IntentFilter().apply {
        addAction(BatteryManager.ACTION_CHARGING)
        addAction(BatteryManager.ACTION_DISCHARGING)
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
    }
    // These are system broadcasts, which reach a receiver that is not exported.
    ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    trySend(isCharging(appContext))
    awaitClose { appContext.unregisterReceiver(receiver) }
}.distinctUntilChanged()
