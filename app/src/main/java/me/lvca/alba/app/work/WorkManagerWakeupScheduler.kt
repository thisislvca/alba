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
     * request made while a run is ending still gets its own run, appended after it. A run waiting under a charging
     * choice the user has since changed is replaced, so it neither waits for a charger nor skips one it now needs.
     */
    override suspend fun scheduleSoon(): Unit = enqueueGate.withLock {
        val current = settings()
        val infos = withContext(Dispatchers.IO) {
            workManager.getWorkInfosForUniqueWork(IMMEDIATE_WORK_NAME).get()
        }
        val policy = immediateWorkPolicy(infos.map { it.state to it.constraints.requiresCharging() }, current.onlyWhileCharging)
            ?: return@withLock
        val request = OneTimeWorkRequestBuilder<BackupWakeWorker>()
            .setConstraints(backupConstraints(NetworkType.CONNECTED, current))
            .setInitialDelay(3, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(IMMEDIATE_WORK_NAME, policy, request)
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
        workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
    }

    private val enqueueGate = Mutex()

    private companion object {
        // Named before the Alba rename and stored on devices; keep it.
        const val IMMEDIATE_WORK_NAME = "mela-photo-maintenance"
        const val PERIODIC_WORK_NAME = "mela-automatic-photo-backup"
    }
}

/** Whether a backup run must be enqueued: none is waiting to start, only running or finished ones. */
internal fun needsAnotherRun(states: List<WorkInfo.State>): Boolean =
    states.none { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED }

/**
 * How to enqueue a backup run soon, given the unique work's runs as (state, requires charging); null when one already
 * waiting covers it. A run that waits under the other charging choice, with none running, is replaced.
 */
internal fun immediateWorkPolicy(runs: List<Pair<WorkInfo.State, Boolean>>, onlyWhileCharging: Boolean): ExistingWorkPolicy? {
    val states = runs.map { it.first }
    if (needsAnotherRun(states)) return ExistingWorkPolicy.APPEND_OR_REPLACE
    val waiting = runs.filter { it.first == WorkInfo.State.ENQUEUED || it.first == WorkInfo.State.BLOCKED }
    val stale = WorkInfo.State.RUNNING !in states && waiting.all { it.first == WorkInfo.State.ENQUEUED && it.second != onlyWhileCharging }
    return if (stale) ExistingWorkPolicy.REPLACE else null
}

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
