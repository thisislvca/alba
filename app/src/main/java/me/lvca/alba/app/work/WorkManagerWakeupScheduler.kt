package me.lvca.alba.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import me.lvca.alba.engine.model.WakeupScheduler
import java.util.concurrent.TimeUnit
import me.lvca.alba.app.BackupWakeWorker
import me.lvca.alba.app.ReconciliationTimerWorker

class WorkManagerWakeupScheduler(context: Context) : WakeupScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val backgroundNetwork = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .build()
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
        val request = PeriodicWorkRequestBuilder<BackupWakeWorker>(15, TimeUnit.MINUTES)
            .setConstraints(backgroundNetwork)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private companion object {
        // Named before the Alba rename and stored on devices; keep it.
        const val IMMEDIATE_WORK_NAME = "mela-photo-maintenance"
        const val PERIODIC_WORK_NAME = "mela-automatic-photo-backup"
    }
}
