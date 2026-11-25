package me.lvca.alba.app.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.messages.appleProtocolError
import me.lvca.alba.engine.repository.FillFailure
import me.lvca.alba.engine.repository.FillOutcome
import me.lvca.alba.engine.repository.fillFailure
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.auth.AppleProtocolError
import me.lvca.alba.protocol.network.AppleMediaHttpException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Saves the thumbnails of the offline set in the background, so every photo in it shows without a connection. */
interface OfflinePhotosScheduler {
    /**
     * Starts the fill once the phone is on Wi-Fi and charging. Work already queued or running is kept, unless
     * [replace]: then it is replaced, as after the budget changed.
     */
    fun schedule(replace: Boolean = false)

    /** Starts the fill again after [delayMillis], once the run that asks for it ends; Apple asked for that wait. */
    fun scheduleAfter(delayMillis: Long) = schedule()

    /** Stops the fill, as when the account is signed out. Saved thumbnails stay. */
    fun cancel()

    companion object {
        /** Schedules nothing, for tests and the demo. */
        val NONE = object : OfflinePhotosScheduler {
            override fun schedule(replace: Boolean) = Unit
            override fun cancel() = Unit
        }
    }
}

internal class WorkManagerOfflinePhotosScheduler(context: Context) : OfflinePhotosScheduler {
    private val workManager by lazy { WorkManager.getInstance(context.applicationContext) }

    override fun schedule(replace: Boolean) {
        workManager.enqueueUniqueWork(WORK_NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request())
    }

    override fun scheduleAfter(delayMillis: Long) {
        // Follows the run that asks for it, under the same name; cancelling the name stops both.
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE,
            request { setInitialDelay(delayMillis, TimeUnit.MILLISECONDS) })
    }

    override fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    private fun request(configure: OneTimeWorkRequest.Builder.() -> Unit = {}): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<OfflinePhotosWorker>()
            .setConstraints(CONSTRAINTS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, OFFLINE_PHOTOS_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .apply(configure)
            .build()

    companion object {
        const val WORK_NAME = "alba-offline-photos"

        /** Only on Wi-Fi or another unmetered network, while charging, with battery and storage not low. */
        val CONSTRAINTS: Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
    }
}

/** WorkManager's first wait after a retry, doubling each time up to its five-hour cap. */
internal const val OFFLINE_PHOTOS_BACKOFF_MILLIS = 60_000L

/** What the worker does after a run. */
internal sealed interface FillDecision {
    /** The run is over: done, or waiting for something that starts it again (signing in, a budget change). */
    data object Done : FillDecision

    /** Try again with WorkManager's exponential backoff. */
    data object Retry : FillDecision

    /** Start again after [delayMillis]: Apple asked for a longer wait than the backoff would give. */
    data class RetryAfter(val delayMillis: Long) : FillDecision
}

/**
 * Turns a run's outcome into what WorkManager does next. Throttling backs off exponentially, and never for less than
 * Apple's Retry-After: when that is longer than the next backoff, the fill is queued again after it instead.
 * [runAttemptCount] is the worker's, which sets the next backoff.
 */
internal fun fillDecision(outcome: FillOutcome, runAttemptCount: Int): FillDecision = when (outcome) {
    is FillOutcome.Finished, FillOutcome.Stopped, FillOutcome.SignedOut -> FillDecision.Done
    FillOutcome.StorageLow, FillOutcome.Interrupted -> FillDecision.Retry
    is FillOutcome.Throttled -> {
        val retryAfter = outcome.retryAfterMillis
        if (retryAfter != null && retryAfter > nextBackoffMillis(runAttemptCount)) FillDecision.RetryAfter(retryAfter)
        else FillDecision.Retry
    }
}

/** WorkManager's exponential backoff after attempt [runAttemptCount] (zero for the first run). */
internal fun nextBackoffMillis(runAttemptCount: Int): Long =
    (OFFLINE_PHOTOS_BACKOFF_MILLIS shl runAttemptCount.coerceIn(0, MAX_BACKOFF_SHIFT)).coerceAtMost(WorkRequest.MAX_BACKOFF_MILLIS)

private const val MAX_BACKOFF_SHIFT = 20

/**
 * What a failed download means for the fill: an expired sign-in stops it without asking anything of the user, a lost
 * connection pauses it, and throttling and full storage as the engine reads them. Anything else, such as one photo's
 * error from Apple, concerns that photo only.
 */
internal fun offlinePhotosFailure(error: Throwable): FillFailure {
    if (error.appleProtocolError() == AppleProtocolError.SESSION_EXPIRED) return FillFailure.SIGNED_OUT
    val kind = fillFailure(error)
    if (kind != FillFailure.ITEM) return kind
    val chain = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
    val connectionLost = error.appleProtocolError() == AppleProtocolError.NETWORK ||
        chain.any { it is IOException && it !is AppleMediaHttpException }
    return if (connectionLost) FillFailure.CONNECTION else FillFailure.ITEM
}

private const val MAX_CAUSE_DEPTH = 8

/**
 * Fills the offline set, newest photo first. The catalog says what is left, so a run stopped by WorkManager (Wi-Fi or
 * charging gone) or by the process ending carries on where it stopped when it starts again.
 */
class OfflinePhotosWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as AlbaApplication
        val graph = app.graph
        val accounts = graph.accountManager
        // A saved account that cannot be read back yet is tried again later, like any other passing failure.
        val restored = runCatching { accounts.restore() }.exceptionOrNull()?.also { if (it is CancellationException) throw it } == null
        val decision = if (restored) accounts.backgroundSessionDecision() else BackgroundSessionDecision.RETRY
        // An expired sign-in waits quietly: signing in again schedules the fill. Only a signed-in iCloud library is
        // filled: never the demo, and nothing once signed out.
        if (decision == BackgroundSessionDecision.RETRY) return Result.retry()
        if (decision == BackgroundSessionDecision.PAUSE || accounts.state.value !is ICloudAccountState.SignedIn) return Result.success()
        val outcome = graph.offlineFill.run(keepGoing = {
            !isStopped && accounts.backgroundSessionDecision() == BackgroundSessionDecision.RUN
        })
        graph.storageLimits.setStorageLow(outcome == FillOutcome.StorageLow)
        return when (val next = fillDecision(outcome, runAttemptCount)) {
            FillDecision.Done -> Result.success()
            FillDecision.Retry -> Result.retry()
            is FillDecision.RetryAfter -> {
                graph.offlinePhotos.scheduleAfter(next.delayMillis)
                Result.success()
            }
        }
    }
}
