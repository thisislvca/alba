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
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.CancelTransfersReceiver
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.R
import me.lvca.alba.app.UserTransferJobService
import me.lvca.alba.app.UserTransferWorker
import java.io.IOException

private const val TRANSFER_JOB_ID = 1701
internal const val TRANSFER_NOTIFICATION_ID = 1702
private const val TRANSFER_CHANNEL = "photo-transfers"

// Named before the Alba rename and stored on devices; keep it.
private const val TRANSFER_WORK = "mela-user-transfers"

internal class UserTransferScheduler(private val context: Context) {
    fun enqueue() {
        if (Build.VERSION.SDK_INT >= 34) {
            val job = JobInfo.Builder(TRANSFER_JOB_ID, ComponentName(context, UserTransferJobService::class.java))
                .setUserInitiated(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()
            // Enqueue wakes a running job without replacing/cancelling its current download.
            check(context.getSystemService(JobScheduler::class.java).enqueue(job, JobWorkItem(Intent())) == JobScheduler.RESULT_SUCCESS) {
                "Could not start transfers. Open Alba and try again."
            }
        } else {
            WorkManager.getInstance(context).enqueueUniqueWork(TRANSFER_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<UserTransferWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
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
    while (graph.batches.runPending()) currentCoroutineContext().ensureActive()
    // Uploads already have a durable journal and are invoked one at a time.
    graph.photos.runUserUploads()
}

internal fun transferNotification(context: Context): Notification {
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(TRANSFER_CHANNEL, context.getString(R.string.transfer_channel), NotificationManager.IMPORTANCE_LOW))
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags)
    val cancel = PendingIntent.getBroadcast(context, 0, Intent(context, CancelTransfersReceiver::class.java), flags)
    return NotificationCompat.Builder(context, TRANSFER_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(context.getString(R.string.transfer_notification_title))
        .setContentText(context.getString(R.string.transfer_notification_body))
        .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
        .setProgress(0, 0, true)
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
