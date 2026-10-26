package me.lvca.alba.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.R
import me.lvca.alba.app.diagnostics.DiagnosticAttribute
import me.lvca.alba.app.diagnostics.DiagnosticOperation
import me.lvca.alba.app.diagnostics.DiagnosticOutcome
import me.lvca.alba.engine.source.CatalogScanAbandoned
import java.util.concurrent.TimeUnit

/** Keeps an unfinished first read of the iCloud library going when Alba is not on screen. */
interface CatalogSyncScheduler {
    /** Continues the read in the background; [expedited] asks Android to start it now, when its quota allows. */
    fun schedule(expedited: Boolean)

    /** Stops background reading, as when the account is signed out. */
    fun cancel()

    companion object {
        /** Schedules nothing, for tests and the demo. */
        val NONE = object : CatalogSyncScheduler {
            override fun schedule(expedited: Boolean) = Unit
            override fun cancel() = Unit
        }
    }
}

internal class WorkManagerCatalogSyncScheduler(context: Context) : CatalogSyncScheduler {
    private val workManager by lazy { WorkManager.getInstance(context.applicationContext) }

    override fun schedule(expedited: Boolean) {
        val request = OneTimeWorkRequestBuilder<CatalogSyncWorker>()
            // A lost connection pauses the read; Android starts it again once the phone is back online.
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .apply { if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
        // KEEP: a read already queued or running continues from its saved page rather than starting again.
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    private companion object {
        const val WORK_NAME = "alba-catalog-sync"
        const val BACKOFF_SECONDS = 30L
    }
}

/**
 * Continues the first library read from its saved page. The read itself is the repository's, so this worker and a
 * refresh on screen never run it twice: whichever comes second waits, then finds nothing left to read.
 */
class CatalogSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as AlbaApplication
        val accounts = app.graph.accountManager
        // An expired sign-in stops here; signing in again starts a refresh, which continues the read. So does a read that
        // kept failing: the next refresh on screen continues it and schedules this work again.
        if (accounts.backgroundSessionDecision() == BackgroundSessionDecision.PAUSE || runAttemptCount >= MAX_ATTEMPTS) {
            return Result.success()
        }
        val span = app.diagnostics.start(DiagnosticOperation.GALLERY_REFRESH, mapOf(DiagnosticAttribute.REASON to "catalog_scan"))
        val outcome = runCatching { resume(app) }
        val error = outcome.exceptionOrNull()
        return when {
            error is CancellationException -> {
                span.finish(DiagnosticOutcome.CANCELLED)
                throw error
            }
            error != null -> {
                span.finish(DiagnosticOutcome.FAILED, error = error)
                // A read that cannot finish as it is was discarded with its rows kept; trying again would not help.
                if (error is CatalogScanAbandoned) return Result.success()
                // A lost connection or throttling tries again later from the saved page; an expired sign-in waits.
                if (accounts.backgroundSessionDecision() == BackgroundSessionDecision.PAUSE) Result.success() else Result.retry()
            }
            else -> outcome.getOrThrow().also { span.finish(DiagnosticOutcome.SUCCESS) }
        }
    }

    private suspend fun resume(app: AlbaApplication): Result {
        app.graph.accountManager.restore()
        return when (app.graph.accountManager.backgroundSessionDecision()) {
            BackgroundSessionDecision.RETRY -> Result.retry()
            BackgroundSessionDecision.PAUSE -> Result.success()
            BackgroundSessionDecision.RUN -> {
                app.graph.galleryRepository.resumeCatalogScan()
                Result.success()
            }
        }
    }

    /** Before Android 12 an expedited job runs as a foreground service, which needs a notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        // The same quiet channel as photo transfers; creating an existing channel changes nothing.
        val channel = NotificationChannel(SYNC_CHANNEL, applicationContext.getString(R.string.transfer_channel),
            NotificationManager.IMPORTANCE_LOW)
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(applicationContext, SYNC_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(applicationContext.getString(R.string.catalog_scan_notification))
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true)
            .build()
        return ForegroundInfo(CATALOG_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private companion object {
        const val CATALOG_NOTIFICATION_ID = 1712

        /** About a day of exponential backoff from 30 seconds, capped by WorkManager at five hours. */
        const val MAX_ATTEMPTS = 10
        const val SYNC_CHANNEL = "photo-transfers"
    }
}
