package me.lvca.alba.app.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import me.lvca.alba.app.R
import me.lvca.alba.engine.model.WakeSummary

internal const val BACKUP_PROGRESS_NOTIFICATION_ID = 1712
private const val BACKUP_PROGRESS_CHANNEL = "backup-progress"

/** Items left to back up, or bytes already queued, from which a run asks to be a foreground job. */
private const val FOREGROUND_ITEMS = 10
private const val FOREGROUND_QUEUED_BYTES = 50L * 1_024 * 1_024

/** Whether backup has enough to do that it should run as a foreground job with a progress notification. */
internal fun runsInForeground(remainingItems: Int, queuedBytes: Long): Boolean =
    remainingItems >= FOREGROUND_ITEMS || queuedBytes >= FOREGROUND_QUEUED_BYTES

/**
 * How long one background backup run keeps going. It starts another pass at once while passes move the queue
 * forward, and waits for work due soon, such as the check in iCloud that follows each upload, rather than ending the
 * run between items. It starts no pass after its budget, and an ordinary background run stays inside WorkManager's ten
 * minutes; a foreground run may take much longer, so a long video can finish.
 */
internal class BackupDrain(private val startedAt: Long, val foreground: Boolean) {
    private val budgetMillis = if (foreground) FOREGROUND_BUDGET_MILLIS else BACKGROUND_BUDGET_MILLIS

    /** The run is cut off here, even mid-upload; the upload is then verified or retried, never replayed blindly. */
    val hardLimitMillis: Long = if (foreground) FOREGROUND_LIMIT_MILLIS else BACKGROUND_LIMIT_MILLIS

    /** Passes in a row that moved nothing, though work was due; reset by any pass that moves the queue. */
    private var idlePasses = 0

    /**
     * How long to wait before the next pass, or null to end the run. A pass that moved nothing never leads straight
     * to another: work that is due but did not start, such as an upload whose iCloud cursor read failed, waits
     * [IDLE_BACKOFF_MILLIS] longer each time, and the run ends after [MAX_IDLE_PASSES] such passes.
     */
    fun nextWait(summary: WakeSummary, now: Long): Long? {
        val left = budgetMillis - (now - startedAt)
        val next = summary.nextWorkAtEpochMillis
        return when {
            left <= 0 -> null
            summary.progressed -> 0L.also { idlePasses = 0 }
            next == null || ++idlePasses > MAX_IDLE_PASSES -> null
            else -> maxOf((next - now).coerceAtLeast(0), IDLE_BACKOFF_MILLIS[(idlePasses - 1).coerceAtMost(IDLE_BACKOFF_MILLIS.lastIndex)])
                .takeIf { it <= MAX_IDLE_WAIT_MILLIS && it < left }
        }
    }

    companion object {
        private const val MINUTE = 60_000L

        /** No run lasts longer than this, whatever it is waiting for. */
        const val LONGEST_RUN_MILLIS = 3 * 60 * MINUTE + MINUTE

        private const val BACKGROUND_BUDGET_MILLIS = 6 * MINUTE
        private const val BACKGROUND_LIMIT_MILLIS = 8 * MINUTE
        private const val FOREGROUND_BUDGET_MILLIS = 60 * MINUTE
        private const val FOREGROUND_LIMIT_MILLIS = 3 * 60 * MINUTE

        /** Waits longer than this end the run; a scheduled wake-up continues later. */
        private const val MAX_IDLE_WAIT_MILLIS = 2 * MINUTE

        /** The least wait after passes that moved nothing, growing with each one in a row. */
        private val IDLE_BACKOFF_MILLIS = longArrayOf(15_000L, 30_000L, MINUTE)

        /** Passes in a row that moved nothing before the run ends. */
        private const val MAX_IDLE_PASSES = 6
    }
}

/** The ongoing notification of a foreground backup run: how much is backed up, opening the Backup page. */
internal fun backupProgressNotification(context: Context, backedUp: Int, eligible: Int): Notification {
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(BACKUP_PROGRESS_CHANNEL, context.getString(R.string.backup_progress_channel), NotificationManager.IMPORTANCE_LOW),
    )
    val total = eligible.coerceAtLeast(0)
    val done = backedUp.coerceIn(0, total)
    return NotificationCompat.Builder(context, BACKUP_PROGRESS_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(context.getString(R.string.backup_progress_notification_title))
        .apply { if (total > 0) setContentText(context.getString(R.string.backup_progress_notification_count, done, total)) }
        .setProgress(total, done, total == 0)
        .setContentIntent(openBackup(context))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .build()
}
