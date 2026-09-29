package dev.mela.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.job.JobWorkItem
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*

private const val TRANSFER_JOB_ID = 1701
private const val TRANSFER_NOTIFICATION_ID = 1702
private const val TRANSFER_CHANNEL = "photo-transfers"
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

/** Android 14+ transfers are independent of ordinary WorkManager job quotas. */
@RequiresApi(34)
class UserTransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runs = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        setNotification(params, TRANSFER_NOTIFICATION_ID, transferNotification(this), JOB_END_NOTIFICATION_POLICY_REMOVE)
        runs[params.jobId] = scope.launch {
            var retry = false
            try {
                withContext(Dispatchers.IO) {
                    while (true) {
                        val item = params.dequeueWork() ?: break
                        runUserTransfers(application as MelaApplication)
                        params.completeWork(item)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { retry = true }
            finally {
                runs.remove(params.jobId, coroutineContext[Job])
                if (isActive) jobFinished(params, retry)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runs.remove(params.jobId)?.cancel()
        return params.stopReason != JobParameters.STOP_REASON_USER && params.stopReason != JobParameters.STOP_REASON_CANCELLED_BY_APP
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class UserTransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        setForeground(ForegroundInfo(TRANSFER_NOTIFICATION_ID, transferNotification(applicationContext),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        return try {
            runUserTransfers(applicationContext as MelaApplication)
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }
}

private suspend fun runUserTransfers(app: MelaApplication) {
    val graph = app.graph
    graph.accountManager.restore()
    when (backgroundSessionDecision(graph.accountManager.state.value, graph.accountManager.session.value != null)) {
        BackgroundSessionDecision.PAUSE -> return
        BackgroundSessionDecision.RETRY -> throw java.io.IOException("Account verification is unavailable")
        BackgroundSessionDecision.RUN -> Unit
    }
    while (graph.batches.runPending()) currentCoroutineContext().ensureActive()
    // Uploads already have a durable journal and are invoked one at a time.
    graph.photos.runUserUploads()
}

private fun transferNotification(context: Context): Notification {
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(TRANSFER_CHANNEL, context.getString(R.string.transfer_channel), NotificationManager.IMPORTANCE_LOW))
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

class CancelTransfersReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                cancelUserTransfers(context)
            } finally { pending.finish() }
        }
    }
}

internal suspend fun cancelUserTransfers(context: Context) {
    val graph = (context.applicationContext as MelaApplication).graph
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
