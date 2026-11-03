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
        val foreground = tryForeground(app)
        val drain = BackupDrain(startedAt = System.currentTimeMillis(), foreground = foreground)
        return withTimeoutOrNull(drain.hardLimitMillis) { runPasses(app, drain) } ?: Result.retry()
    }

    private suspend fun runPasses(app: AlbaApplication, drain: BackupDrain): Result {
        val span = app.diagnostics.start(
            DiagnosticOperation.MAINTENANCE_WAKE,
            mapOf(DiagnosticAttribute.REASON to if (drain.foreground) "background-foreground" else "background"),
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
            var passes = 0
            var recovered = 0
            var reconciled = 0
            var invoked = 0
            var staged = 0
            while (true) {
                val summary = app.graph.maintenance.wake(WakeReason.BACKGROUND)
                passes += 1
                recovered += summary.recoveredInvocations
                reconciled += summary.reconciledTransfers
                invoked += summary.invokedTransfers
                staged += summary.stagedAutomaticTransfers
                if (drain.foreground) updateProgress(app)
                val waitMillis = drain.nextWait(summary, System.currentTimeMillis()) ?: break
                if (waitMillis > 0) delay(waitMillis)
            }
            // Storage full and failed items need the user; routine progress posts nothing.
            report(app, signInNeeded = false)
            span.finish(
                DiagnosticOutcome.SUCCESS,
                mapOf(
                    DiagnosticAttribute.RECOVERED_COUNT to recovered,
                    DiagnosticAttribute.RECONCILED_COUNT to reconciled,
                    DiagnosticAttribute.STAGED_COUNT to staged,
                    DiagnosticAttribute.INVOKED_COUNT to invoked,
                    DiagnosticAttribute.MORE_WORK to (passes > 1),
                ),
            )
            Result.success()
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

    /**
     * Runs in the foreground when backup has a lot to do. Android 12 and later refuse this while Alba is in the
     * background unless an exemption applies; the run then continues as ordinary background work.
     */
    private suspend fun tryForeground(app: AlbaApplication): Boolean = try {
        val progress = app.graph.photos.backup.backupProgress()
        val queuedBytes = app.graph.photos.observeTransfers().first()
            .filter { it.automatic && it.state in QUEUED }.sumOf { it.byteCount }
        if (!runsInForeground(progress.eligible - progress.backedUp, queuedBytes)) false
        else {
            setForeground(foregroundInfo(app, progress.backedUp, progress.eligible))
            true
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Includes ForegroundServiceStartNotAllowedException: the work still runs, within the background limits.
        false
    }

    private suspend fun updateProgress(app: AlbaApplication) {
        try {
            val progress = app.graph.photos.backup.backupProgress()
            setForeground(foregroundInfo(app, progress.backedUp, progress.eligible))
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

    private companion object {
        val QUEUED = setOf(TransferViewState.WAITING, TransferViewState.UPLOADING)
    }
}
