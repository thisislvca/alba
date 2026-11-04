package me.lvca.alba.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    /**
     * Asks for a backup run soon. A run already waiting covers this request, so at most one waits behind the one
     * running: a draining run asks after every item, and appending each time would leave a chain of empty runs. A
     * request made while a run is ending still gets its own run, appended after it.
     *
     * Runs that need the charger wait under their own unique name. Changing "Only while charging" therefore never
     * replaces or cancels anything, so a foreground batch that is running keeps running: the next request simply goes
     * under the name for the new choice, and starts as soon as that choice allows, even with a run of the other choice
     * still queued. A leftover run of the other choice is harmless: the engine applies the current settings to every
     * pass, so it uploads nothing the user no longer allows, and the engine runs one pass at a time.
     */
    override suspend fun scheduleSoon(): Unit = enqueueGate.withLock {
        val current = settings()
        val name = immediateWorkName(current.onlyWhileCharging)
        val states = withContext(Dispatchers.IO) {
            workManager.getWorkInfosForUniqueWork(name).get().map { it.state }
        }
        if (!needsAnotherRun(states)) return@withLock
        val request = OneTimeWorkRequestBuilder<BackupWakeWorker>()
            .setConstraints(backupConstraints(NetworkType.CONNECTED, current))
            .setInitialDelay(3, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        Unit
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
        // UPDATE keeps the period's schedule and applies the networks and charging the user's settings now ask for.
        val current = settings()
        val request = PeriodicWorkRequestBuilder<BackupWakeWorker>(15, TimeUnit.MINUTES)
            .setConstraints(backupConstraints(backupNetworkType(current), current))
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override suspend fun cancelRunningBackup() {
        // An upload this interrupts resumes later or goes to read-only verification; it is never sent again blindly.
        IMMEDIATE_WORK_NAMES.forEach(workManager::cancelUniqueWork)
    }

    private val enqueueGate = Mutex()

    internal companion object {
        // Named before the Alba rename and stored on devices; keep it.
        const val IMMEDIATE_WORK_NAME = "mela-photo-maintenance"
        const val IMMEDIATE_CHARGING_WORK_NAME = "alba-photo-maintenance-charging"
        const val PERIODIC_WORK_NAME = "mela-automatic-photo-backup"
        private val IMMEDIATE_WORK_NAMES = listOf(IMMEDIATE_WORK_NAME, IMMEDIATE_CHARGING_WORK_NAME)

        /** The unique name runs soon wait under, one per charging choice, so one choice's runs never replace the other's. */
        fun immediateWorkName(onlyWhileCharging: Boolean): String =
            if (onlyWhileCharging) IMMEDIATE_CHARGING_WORK_NAME else IMMEDIATE_WORK_NAME
    }
}

/** Whether a backup run must be enqueued: none is waiting to start, only running or finished ones. */
internal fun needsAnotherRun(states: List<WorkInfo.State>): Boolean =
    states.none { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED }

/**
 * What every backup run waits for: [network], and the charger when the user chose "Only while charging". WorkManager
 * stops a run when the phone is unplugged; the upload it interrupts resumes from the journal, never blindly.
 */
internal fun backupConstraints(network: NetworkType, settings: BackupSettings): Constraints = Constraints.Builder()
    .setRequiredNetworkType(network)
    .setRequiresCharging(settings.onlyWhileCharging)
    .build()

/** The least the periodic backup pass needs from the network, given what the user allows. */
internal fun backupNetworkType(settings: BackupSettings): NetworkType = when {
    !settings.usesMobileData -> NetworkType.UNMETERED
    settings.whileRoaming -> NetworkType.CONNECTED
    else -> NetworkType.NOT_ROAMING
}
