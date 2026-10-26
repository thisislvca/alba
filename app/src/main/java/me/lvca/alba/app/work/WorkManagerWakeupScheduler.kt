package me.lvca.alba.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import me.lvca.alba.app.BackupWakeWorker
import me.lvca.alba.app.ReconciliationTimerWorker
import me.lvca.alba.engine.model.BackupSettings
import me.lvca.alba.engine.model.WakeupScheduler
import java.util.concurrent.TimeUnit

class WorkManagerWakeupScheduler(
    context: Context,
    private val settings: () -> BackupSettings = { BackupSettings() },
) : WakeupScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val connectedNetwork = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    override suspend fun scheduleSoon() {
        val request = OneTimeWorkRequestBuilder<BackupWakeWorker>()
            .setConstraints(connectedNetwork)
            .setInitialDelay(3, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    override suspend fun scheduleAt(notBeforeEpochMillis: Long) {
        // A separate timer avoids cancelling the maintenance worker that schedules it.
        val request = OneTimeWorkRequestBuilder<ReconciliationTimerWorker>()
            .setInitialDelay((notBeforeEpochMillis - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .build()
        // Named before the Alba rename and stored on devices; keep it.
        workManager.enqueueUniqueWork("mela-reconciliation-timer", ExistingWorkPolicy.REPLACE, request)
    }

    override suspend fun setAutomaticBackupEnabled(enabled: Boolean) {
        if (!enabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }
        // UPDATE keeps the period's schedule and applies the networks the user's settings now allow.
        val request = PeriodicWorkRequestBuilder<BackupWakeWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(backupNetworkType(settings())).build())
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override suspend fun cancelRunningBackup() {
        // An upload this interrupts resumes later or goes to read-only verification; it is never sent again blindly.
        workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
    }

    private companion object {
        // Named before the Alba rename and stored on devices; keep it.
        const val IMMEDIATE_WORK_NAME = "mela-photo-maintenance"
        const val PERIODIC_WORK_NAME = "mela-automatic-photo-backup"
    }
}

/** The least the periodic backup pass needs from the network, given what the user allows. */
internal fun backupNetworkType(settings: BackupSettings): NetworkType = when {
    !settings.usesMobileData -> NetworkType.UNMETERED
    settings.whileRoaming -> NetworkType.CONNECTED
    else -> NetworkType.NOT_ROAMING
}
