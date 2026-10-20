package me.lvca.alba.app

import android.app.job.JobParameters
import android.app.job.JobService
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lvca.alba.app.work.TRANSFER_NOTIFICATION_ID
import me.lvca.alba.app.work.runUserTransfers
import me.lvca.alba.app.work.transferNotification

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
                        runUserTransfers(application as AlbaApplication)
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
