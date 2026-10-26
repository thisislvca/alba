package me.lvca.alba.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import me.lvca.alba.app.diagnostics.DiagnosticAttribute
import me.lvca.alba.app.diagnostics.DiagnosticOperation
import me.lvca.alba.app.diagnostics.DiagnosticOutcome
import me.lvca.alba.app.work.BackgroundSessionDecision
import me.lvca.alba.app.work.backgroundSessionDecision
import me.lvca.alba.engine.model.WakeReason

class BackupWakeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withTimeoutOrNull(8 * 60_000L) { runPass() } ?: Result.retry()

    private suspend fun runPass(): Result {
        val app = applicationContext as AlbaApplication
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
            val summary = app.graph.maintenance.wake(WakeReason.BACKGROUND)
            // Storage full and failed items need the user; routine progress posts nothing.
            report(app, signInNeeded = false)
            span.finish(
                DiagnosticOutcome.SUCCESS,
                mapOf(
                    DiagnosticAttribute.RECOVERED_COUNT to summary.recoveredInvocations,
                    DiagnosticAttribute.RECONCILED_COUNT to summary.reconciledTransfers,
                    DiagnosticAttribute.STAGED_COUNT to summary.stagedAutomaticTransfers,
                    DiagnosticAttribute.INVOKED_COUNT to summary.invokedTransfers,
                    DiagnosticAttribute.MORE_WORK to summary.moreWorkScheduled,
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
     * Tells the user, once, what stopped backup: an expired sign-in, a full iCloud, or items that failed. A pass that
     * only made progress posts nothing. Reporting never fails the pass.
     */
    private suspend fun report(app: AlbaApplication, signInNeeded: Boolean) =
        app.graph.backupNotifications.report(app.graph.photos, signInNeeded)
}
