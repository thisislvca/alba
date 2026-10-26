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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.R
import me.lvca.alba.app.intake.SharedActivityLinks
import me.lvca.alba.app.sharing.SharedActivityStore
import me.lvca.alba.app.sharing.SharedAlbumMark
import me.lvca.alba.app.sharing.SharedAlbumNews
import me.lvca.alba.engine.model.SharedAlbumCount
import me.lvca.alba.engine.model.SharedInvitation
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus
import java.util.concurrent.TimeUnit

private const val SHARED_CHANNEL = "shared-albums"
private const val SHARED_GROUP = "me.lvca.alba.SHARED_ALBUMS"
private const val SHARED_SUMMARY_ID = 2100
private const val SHARED_INVITATION_ID = 2101
private const val SHARED_ALBUM_ID = 2102
private const val INVITATIONS_REQUEST = 2201
private const val SUMMARY_REQUEST = 2202
private const val SHARED_WORK = "shared-album-activity"
private const val CHECK_EVERY_HOURS = 6L
private const val CHECK_TIMEOUT_MILLIS = 5 * 60_000L

/** Checks shared albums for new invitations and photos a few times a day while notifications are on. */
internal object SharedActivitySchedule {
    fun apply(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            work.cancelUniqueWork(SHARED_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<SharedActivityWorker>(CHECK_EVERY_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .build()
        work.enqueueUniquePeriodicWork(SHARED_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/**
 * Reads shared albums and invitations from Apple, without refreshing the rest of the library, and compares each album
 * with what the person last saw. It reads no comments: Alba has no cheap signal for them, so only invitations and new
 * photos are announced.
 */
class SharedActivityWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withTimeoutOrNull(CHECK_TIMEOUT_MILLIS) { check() } ?: Result.retry()

    private suspend fun check(): Result {
        val app = applicationContext as AlbaApplication
        val store = app.graph.sharedActivity
        if (!store.state.value.notificationsEnabled || !canNotify(applicationContext)) return Result.success()
        val accounts = app.graph.accountManager
        accounts.restore()
        val decision = accounts.backgroundSessionDecision()
        val account = (accounts.state.value as? ICloudAccountState.SignedIn)?.takeIf { it.status == SessionStatus.VERIFIED }
        // Only a verified account has real invitations and albums to announce; never the demo library.
        if (decision != BackgroundSessionDecision.RUN || account == null) {
            return if (decision == BackgroundSessionDecision.RETRY) Result.retry() else Result.success()
        }
        val repository = app.graph.galleryRepository
        val sameAccount = { (accounts.state.value as? ICloudAccountState.SignedIn)?.appleId == account.appleId }
        return runCatching {
            announce(applicationContext, store, repository::sharedAlbumCounts, repository::pendingSharedInvitations, sameAccount)
        }.fold({ Result.success() }, { if (it is CancellationException) throw it else Result.retry() })
    }
}

/**
 * Posts what is new since the last look. What is announced is recorded first, and every notification has a stable
 * tag and id, so a retry after a crash replaces a notification rather than adding another. Nothing is written once
 * the ledger was cleared or the account changed during the check.
 */
internal suspend fun announce(context: Context, store: SharedActivityStore, readCounts: suspend () -> List<SharedAlbumCount>,
    readInvitations: suspend () -> List<SharedInvitation>, sameAccount: () -> Boolean = { true }) {
    val generation = store.generation
    val counts = readCounts()
    val invitations = readInvitations()
    val current = SharedAlbumMark.of(counts)
    val ledger = if (sameAccount()) {
        store.updateIf(generation) { it.observe(current).observeInvitations(invitations.map(SharedInvitation::id).toSet()) }
    } else null
    if (ledger == null || !ledger.notificationsEnabled || !canNotify(context)) return
    val news = ledger.news(current, counts.associate { it.albumId to it.name })
    val fresh = invitations.filter { it.id in ledger.newInvitations }
    val recorded = (news.isNotEmpty() || fresh.isNotEmpty()) &&
        store.updateIf(generation) { it.announce(news, current).announceInvitations(fresh.map(SharedInvitation::id).toSet()) } != null
    if (recorded && sameAccount()) postSharedActivity(context, news, fresh)
}

internal fun canNotify(context: Context): Boolean {
    val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
}

/** Removes every shared-album notification, for example when the account signs out. */
internal fun cancelSharedActivityNotifications(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.activeNotifications.filter { it.notification.group == SHARED_GROUP }.forEach { manager.cancel(it.tag, it.id) }
}

/** Opens Alba at what a notification is about: an album, the invitations inbox, or the Shared Albums page. */
private fun openIntent(context: Context, requestCode: Int, extras: Intent.() -> Unit): PendingIntent =
    PendingIntent.getActivity(context, requestCode,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).apply(extras),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

@Suppress("MissingPermission") // canNotify checked the permission.
private fun postSharedActivity(context: Context, news: List<SharedAlbumNews>, invitations: List<SharedInvitation>) {
    val manager = NotificationManagerCompat.from(context)
    val channel = NotificationChannel(SHARED_CHANNEL, context.getString(R.string.shared_notification_channel), NotificationManager.IMPORTANCE_DEFAULT)
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    // The lock screen shows only that something happened, never album or people names.
    val public = NotificationCompat.Builder(context, SHARED_CHANNEL).setSmallIcon(R.drawable.ic_notification_shared)
        .setContentTitle(context.getString(R.string.shared_notification_public)).build()
    fun builder(open: PendingIntent) = NotificationCompat.Builder(context, SHARED_CHANNEL).setSmallIcon(R.drawable.ic_notification_shared)
        .setContentIntent(open).setAutoCancel(true).setGroup(SHARED_GROUP).setCategory(NotificationCompat.CATEGORY_SOCIAL)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
    val resources = context.resources
    if (invitations.isNotEmpty()) manager.notify(SHARED_INVITATION_ID,
        builder(openIntent(context, INVITATIONS_REQUEST) { putExtra(SharedActivityLinks.INVITATIONS, true) })
            .setContentTitle(resources.getQuantityString(R.plurals.shared_notification_invitations, invitations.size, invitations.size))
            .setContentText(invitations.joinToString(", ") { it.name })
            .build())
    news.forEach { item ->
        manager.notify(item.albumId, SHARED_ALBUM_ID,
            builder(openIntent(context, item.albumId.hashCode()) { putExtra(SharedActivityLinks.ALBUM, item.albumId) })
                .setContentTitle(context.getString(R.string.shared_notification_new_photos, item.name))
                .setContentText(resources.getQuantityString(R.plurals.shared_notification_new_items, item.newItems, item.newItems))
                .build())
    }
    // Android bundles a group only once it has a summary.
    if (news.size + (if (invitations.isEmpty()) 0 else 1) > 1) manager.notify(SHARED_SUMMARY_ID,
        builder(openIntent(context, SUMMARY_REQUEST) { putExtra(SharedActivityLinks.SHARED_ALBUMS, true) })
            .setContentTitle(context.getString(R.string.shared_notification_channel))
            .setGroupSummary(true)
            .build())
}
