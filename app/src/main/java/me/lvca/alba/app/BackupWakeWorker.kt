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
            if (backgroundSessionDecision(
                    state = app.graph.accountManager.state.value,
                    hasSavedSession = app.graph.accountManager.session.value != null,
                ) == BackgroundSessionDecision.PAUSE
            ) {
                span.finish(DiagnosticOutcome.SUCCESS)
                return Result.success()
            }
            app.graph.accountManager.restore()
            when (backgroundSessionDecision(
                state = app.graph.accountManager.state.value,
                hasSavedSession = app.graph.accountManager.session.value != null,
            )) {
                BackgroundSessionDecision.RUN -> Unit
                BackgroundSessionDecision.RETRY -> {
                    span.finish(DiagnosticOutcome.FAILED)
                    return Result.retry()
                }
                BackgroundSessionDecision.PAUSE -> {
                    span.finish(DiagnosticOutcome.SUCCESS)
                    return Result.success()
                }
            }
            val summary = app.graph.maintenance.wake(WakeReason.BACKGROUND)
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
            if (backgroundSessionDecision(
                    state = app.graph.accountManager.state.value,
                    hasSavedSession = app.graph.accountManager.session.value != null,
                ) == BackgroundSessionDecision.PAUSE
            ) Result.success() else Result.retry()
        }
    }
}
