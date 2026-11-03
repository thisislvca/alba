package me.lvca.alba.app

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.lvca.alba.app.diagnostics.DiagnosticAttribute
import me.lvca.alba.app.diagnostics.DiagnosticOperation
import me.lvca.alba.app.diagnostics.DiagnosticOutcome
import me.lvca.alba.app.work.BACKUP_PROGRESS_NOTIFICATION_ID
import me.lvca.alba.app.work.BackgroundSessionDecision
import me.lvca.alba.app.work.BackupDrain
import me.lvca.alba.app.work.backgroundSessionDecision
import me.lvca.alba.app.work.backupProgressNotification
import me.lvca.alba.app.work.runsInForeground
import me.lvca.alba.engine.model.TransferViewState
import me.lvca.alba.engine.model.WakeReason

/**
 * Automatic backup in the background. It keeps running passes while they move the queue forward, waiting briefly for
 * a check in iCloud that is due soon, so a large backlog drains in one run rather than one item per run. With a lot to
 * do it asks to run as a foreground data-sync job with a progress notification, which also lets a long video finish;
 * Android may refuse that from the background, and then the run stays within the ordinary background time limit.
 */
class BackupWakeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as AlbaApplication
        val startedAt = System.currentTimeMillis()
        return withTimeoutOrNull(BackupDrain.LONGEST_RUN_MILLIS) { runPasses(app, startedAt) } ?: Result.retry()
    }

    private suspend fun runPasses(app: AlbaApplication, startedAt: Long): Result {
        val span = app.diagnostics.start(
            DiagnosticOperation.MAINTENANCE_WAKE,
            mapOf(DiagnosticAttribute.REASON to "background"),
        )
        return try {
            if (app.graph.accountManager.backgroundSessionDecision() == BackgroundSessionDecision.PAUSE) {
                report(app, signInNeeded = true)
                span.finish(DiagnosticOutcome.SUCCESS)
                return Result.success()
            }
            app.graph.accountManager.restore()
            when (app.graph.accountManager.backgroundSessionDecision()) {
                BackgroundSessionDecision.RUN -> Unit
                BackgroundSessionDecision.RETRY -> {
                    span.finish(DiagnosticOutcome.FAILED)
                    return Result.retry()
                }
                BackgroundSessionDecision.PAUSE -> {
                    report(app, signInNeeded = true)
                    span.finish(DiagnosticOutcome.SUCCESS)
                    return Result.success()
                }
            }
            // Decided once the session is restored, since what is left to back up depends on the account.
            val drain = BackupDrain(startedAt, foreground = tryForeground(app, startedAt))
            val totals = drainQueue(app, drain, startedAt)
            if (totals == null) {
                // Cut off mid-pass: an upload it stopped is verified or retried from the journal, never replayed blindly.
                span.finish(DiagnosticOutcome.CANCELLED)
                Result.retry()
            } else {
                // Storage full and failed items need the user; routine progress posts nothing.
                report(app, signInNeeded = false)
                span.finish(
                    DiagnosticOutcome.SUCCESS,
                    mapOf(
                        DiagnosticAttribute.RECOVERED_COUNT to totals.recovered,
                        DiagnosticAttribute.RECONCILED_COUNT to totals.reconciled,
                        DiagnosticAttribute.STAGED_COUNT to totals.staged,
                        DiagnosticAttribute.INVOKED_COUNT to totals.invoked,
                        DiagnosticAttribute.MORE_WORK to (totals.passes > 1),
                    ),
                )
                Result.success()
            }
        } catch (cancelled: CancellationException) {
            span.finish(DiagnosticOutcome.CANCELLED)
            throw cancelled
        } catch (error: Exception) {
            span.finish(DiagnosticOutcome.FAILED, error = error)
            // Retry the durable state machine, never replay an uncertain HTTP upload.
            if (app.graph.accountManager.backgroundSessionDecision() == BackgroundSessionDecision.PAUSE) {
                report(app, signInNeeded = true)
                Result.success()
            } else Result.retry()
        }
    }

    /** What one run did, for diagnostics. */
    private data class DrainTotals(var passes: Int = 0, var recovered: Int = 0, var reconciled: Int = 0, var invoked: Int = 0, var staged: Int = 0)

    /** Runs passes until [drain] ends the run; null when the run was cut off at its hard limit. */
    private suspend fun drainQueue(app: AlbaApplication, drain: BackupDrain, startedAt: Long): DrainTotals? {
        val totals = DrainTotals()
        var progressShownAt = System.currentTimeMillis()
        return withTimeoutOrNull(drain.hardLimitMillis - (System.currentTimeMillis() - startedAt)) {
            while (true) {
                val summary = app.graph.maintenance.wake(WakeReason.BACKGROUND)
                totals.passes += 1
                totals.recovered += summary.recoveredInvocations
                totals.reconciled += summary.reconciledTransfers
                totals.invoked += summary.invokedTransfers
                totals.staged += summary.stagedAutomaticTransfers
                if (drain.foreground && System.currentTimeMillis() - progressShownAt >= PROGRESS_INTERVAL_MILLIS) {
                    updateProgress(app)
                    progressShownAt = System.currentTimeMillis()
                }
                val waitMillis = drain.nextWait(summary, System.currentTimeMillis()) ?: break
                if (waitMillis > 0) delay(waitMillis)
            }
            totals
        }
    }

    /**
     * Runs in the foreground when backup has a lot to do. Android 12 and later refuse this while Alba is in the
     * background unless an exemption applies; the run then continues as ordinary background work.
     */
    private suspend fun tryForeground(app: AlbaApplication, startedAt: Long): Boolean = try {
        // Counted once per run: it reads every item backup covers. Skipped and failed items are not pending work.
        val progress = app.graph.photos.backup.backupProgress()
        runTotal = progress.pending
        runStartedAt = startedAt
        val queuedBytes = app.graph.photos.observeTransfers().first()
            .filter { it.automatic && it.state in QUEUED }.sumOf { it.byteCount }
        if (!runsInForeground(progress.pending, queuedBytes)) false
        else {
            setForeground(foregroundInfo(app, 0, runTotal))
            true
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Includes ForegroundServiceStartNotAllowedException: the work still runs, within the background limits.
        false
    }

    /** What this run has verified out of what was pending when it started, from one cheap count. */
    private suspend fun updateProgress(app: AlbaApplication) {
        try {
            val done = app.graph.photos.backup.verifiedSince(runStartedAt)
            setForeground(foregroundInfo(app, done, maxOf(runTotal, done)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A stale progress line is not worth stopping the backup for.
        }
    }

    private fun foregroundInfo(app: AlbaApplication, backedUp: Int, eligible: Int) = ForegroundInfo(
        BACKUP_PROGRESS_NOTIFICATION_ID,
        backupProgressNotification(app, backedUp, eligible),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    /**
     * Tells the user, once, what stopped backup: an expired sign-in, a full iCloud, or items that failed. A pass that
     * only made progress posts nothing. Reporting never fails the pass.
     */
    private suspend fun report(app: AlbaApplication, signInNeeded: Boolean) =
        app.graph.backupNotifications.report(app.graph.photos, signInNeeded)

    /** What was pending when this run started, and when it started, for its progress line. */
    private var runTotal = 0
    private var runStartedAt = 0L

    private companion object {
        val QUEUED = setOf(TransferViewState.WAITING, TransferViewState.UPLOADING)

        /** Counting progress reads every covered item, so the notification is refreshed at most this often. */
        const val PROGRESS_INTERVAL_MILLIS = 30_000L
    }
}
