package me.lvca.alba.app.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobWorkItem
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.CancelTransfersReceiver
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.R
import me.lvca.alba.app.UserTransferJobService
import me.lvca.alba.app.UserTransferWorker
import me.lvca.alba.engine.companion.BatchAction
import java.io.IOException

private const val TRANSFER_JOB_ID = 1701
internal const val TRANSFER_NOTIFICATION_ID = 1702
private const val TRANSFER_CHANNEL = "photo-transfers"

// Named before the Alba rename and stored on devices; keep it.
private const val TRANSFER_WORK = "mela-user-transfers"

internal class UserTransferScheduler(private val context: Context) {
    /** Starts the user's transfers; with [wifiOnly] they wait for an unmetered network. */
    fun enqueue(wifiOnly: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 34) {
            val job = JobInfo.Builder(TRANSFER_JOB_ID, ComponentName(context, UserTransferJobService::class.java))
                .setUserInitiated(true)
                .setRequiredNetworkType(if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                .build()
            // Enqueue wakes a running job without replacing/cancelling its current download.
            check(context.getSystemService(JobScheduler::class.java).enqueue(job, JobWorkItem(Intent())) == JobScheduler.RESULT_SUCCESS) {
                "Could not start transfers. Open Alba and try again."
            }
        } else {
            WorkManager.getInstance(context).enqueueUniqueWork(TRANSFER_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<UserTransferWorker>()
                    .setConstraints(Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()).build())
        }
    }
}

internal suspend fun runUserTransfers(app: AlbaApplication) {
    val graph = app.graph
    graph.accountManager.restore()
    when (graph.accountManager.backgroundSessionDecision()) {
        BackgroundSessionDecision.PAUSE -> return
        BackgroundSessionDecision.RETRY -> throw IOException("Account verification is unavailable")
        BackgroundSessionDecision.RUN -> Unit
    }
    val startedAt = System.currentTimeMillis()
    coroutineScope {
        // Determinate progress: what this run has finished out of everything it holds.
        val progress = launch {
            combine(graph.photos.observeTransfers(), graph.batches.observe()) { _, batches -> batches }.collectLatest { batches ->
                val uploads = graph.photos.backup.userUploadProgress(startedAt)
                val downloads = batches.filter { it.action in DownloadActions && (it.status.isPending || it.createdAt >= startedAt) }
                val done = uploads.done + downloads.count { !it.status.isPending }
                val total = uploads.total + downloads.size
                if (total > 0 && canPost(app)) {
                    app.getSystemService(NotificationManager::class.java)
                        .notify(TRANSFER_NOTIFICATION_ID, transferNotification(app, done, total, uploadsOnly = downloads.isEmpty()))
                }
            }
        }
        try {
            while (graph.batches.runPending()) currentCoroutineContext().ensureActive()
            // Uploads already have a durable journal and are invoked one at a time.
            graph.photos.runUserUploads()
        } finally {
            progress.cancel()
        }
    }
    // A full iCloud stops the run; say so once rather than leaving the queue silently waiting.
    graph.backupNotifications.report(graph.photos, signInNeeded = false)
}

private val DownloadActions = setOf(BatchAction.KEEP_OFFLINE.name, BatchAction.SAVE_TO_PHONE.name)

internal fun transferNotification(context: Context, done: Int = 0, total: Int = 0, uploadsOnly: Boolean = true): Notification {
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(TRANSFER_CHANNEL, context.getString(R.string.transfer_channel), NotificationManager.IMPORTANCE_LOW))
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags)
    val cancel = PendingIntent.getBroadcast(context, 0, Intent(context, CancelTransfersReceiver::class.java), flags)
    return NotificationCompat.Builder(context, TRANSFER_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(when {
            total == 0 -> context.getString(R.string.transfer_notification_title)
            uploadsOnly -> context.getString(R.string.transfer_notification_uploading, done.coerceAtMost(total), total)
            else -> context.getString(R.string.transfer_notification_progress, done.coerceAtMost(total), total)
        })
        .setContentText(context.getString(R.string.transfer_notification_body))
        .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
        .setProgress(total, done.coerceAtMost(total), total == 0)
        .addAction(0, context.getString(R.string.transfer_cancel), cancel)
        .build()
}

internal suspend fun cancelUserTransfers(context: Context) {
    val graph = (context.applicationContext as AlbaApplication).graph
    // An upload can be staged while batch cancellation joins its coroutine.
    // Stop the existing upload queue, then close that staging window as well.
    try {
        graph.photos.cancelUserUploads()
        graph.batches.cancelActive()
        graph.photos.cancelUserUploads()
    } finally {
        context.getSystemService(JobScheduler::class.java).cancel(TRANSFER_JOB_ID)
        WorkManager.getInstance(context).cancelUniqueWork(TRANSFER_WORK)
    }
}
