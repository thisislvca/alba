package me.lvca.alba.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.R
import me.lvca.alba.app.platform.BackupPreferences
import me.lvca.alba.engine.model.PhotoCompanion
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.TransferViewState

/** The intent extra that opens the Backup page; a notification tap sets it. */
internal const val EXTRA_OPEN_BACKUP = "me.lvca.alba.OPEN_BACKUP"

private const val STATUS_CHANNEL = "backup-status"
private const val PAUSED_NOTIFICATION_ID = 1710
private const val FAILED_NOTIFICATION_ID = 1711

/** Why backup stopped and needs the user. Each is reported once, until it changes. */
internal enum class BackupPause { SIGN_IN, STORAGE_FULL }

/**
 * Backup results that need the user, on a quiet channel of their own: backup paused for sign-in, iCloud storage full,
 * and items that could not be backed up. Routine completion posts nothing, as in Google Photos. Each opens the Backup
 * page.
 */
internal class BackupNotifications(
    private val context: Context,
    private val preferences: BackupPreferences,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    /**
     * Reports what changed since the last pass: a new pause, a pause that ended, or newly failed backup items. It never
     * fails the work that calls it: the Backup page shows the same state.
     */
    suspend fun report(photos: PhotoCompanion, signInNeeded: Boolean) {
        try {
            reportChanges(photos, signInNeeded)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A lost notification is not worth failing or retrying a transfer run.
        }
    }

    private suspend fun reportChanges(photos: PhotoCompanion, signInNeeded: Boolean) {
        val backup = photos.observeBackup().first()
        val pause = backupPause(backup.enabled, signInNeeded, backup.storageFullSinceEpochMillis)
        val pauseKey = pause?.let { "${it.name}:${backup.storageFullSinceEpochMillis ?: 0}" }
        if (pauseKey != preferences.pauseNotified) {
            if (pause == null) manager.cancel(PAUSED_NOTIFICATION_ID) else post(PAUSED_NOTIFICATION_ID, pausedTitle(pause), pausedBody(pause))
            preferences.pauseNotified = pauseKey
        }
        if (signInNeeded || !backup.enabled) return
        val failed = newlyFailed(photos.observeTransfers().first(), preferences.failuresNotifiedThrough)
        if (failed.isNotEmpty()) {
            post(
                FAILED_NOTIFICATION_ID,
                context.resources.getQuantityString(R.plurals.backup_failed_notification_title, failed.size, failed.size),
                context.getString(R.string.backup_failed_notification_body),
            )
            preferences.failuresNotifiedThrough = failed.maxOf { it.updatedAtEpochMillis }
        }
    }

    private fun pausedTitle(pause: BackupPause) = context.getString(when (pause) {
        BackupPause.SIGN_IN -> R.string.backup_paused_sign_in_title
        BackupPause.STORAGE_FULL -> R.string.backup_paused_storage_title
    })

    private fun pausedBody(pause: BackupPause) = context.getString(when (pause) {
        BackupPause.SIGN_IN -> R.string.backup_paused_sign_in_body
        BackupPause.STORAGE_FULL -> R.string.backup_paused_storage_body
    })

    private fun post(id: Int, title: String, body: String) {
        if (!canPost(context)) return
        manager.createNotificationChannel(
            NotificationChannel(STATUS_CHANNEL, context.getString(R.string.backup_status_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.backup_status_channel_description)
            },
        )
        manager.notify(id, NotificationCompat.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openBackup(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build())
    }
}

/** What pauses backup now, if anything: an expired sign-in comes first, since nothing can be checked without it. */
internal fun backupPause(enabled: Boolean, signInNeeded: Boolean, storageFullSince: Long?): BackupPause? = when {
    signInNeeded && enabled -> BackupPause.SIGN_IN
    storageFullSince != null -> BackupPause.STORAGE_FULL
    else -> null
}

/** Backup items that failed or were skipped after [notifiedThrough]. Items the user chose to skip are not failures. */
internal fun newlyFailed(transfers: List<TransferView>, notifiedThrough: Long): List<TransferView> = transfers.filter {
    it.automatic && it.updatedAtEpochMillis > notifiedThrough &&
        it.state in setOf(TransferViewState.NEEDS_ATTENTION, TransferViewState.UNSUPPORTED, TransferViewState.UNRESOLVED)
}

internal fun canPost(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** Opens Alba on the Backup page. */
internal fun openBackup(context: Context): PendingIntent = PendingIntent.getActivity(
    context,
    1,
    Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_BACKUP, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)
