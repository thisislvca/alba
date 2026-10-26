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
import me.lvca.alba.app.sharing.SharedActivityStore
import me.lvca.alba.app.sharing.SharedAlbumMark
import me.lvca.alba.app.sharing.SharedAlbumNews
import me.lvca.alba.engine.model.GalleryRepository
import me.lvca.alba.engine.model.SharedInvitation
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus
import java.util.concurrent.TimeUnit

private const val SHARED_CHANNEL = "shared-albums"
private const val SHARED_GROUP = "me.lvca.alba.SHARED_ALBUMS"
private const val SHARED_SUMMARY_ID = 2100
private const val SHARED_INVITATION_ID = 2101
private const val SHARED_ALBUM_ID = 2102
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
 * Refreshes the library the way opening Alba does, then compares each shared album with what the person last saw. It
 * reads no comments: Alba has no cheap signal for them, so only invitations and new photos are announced.
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
        // Only a verified account has real invitations and albums to announce; never the demo library.
        val account = accounts.state.value
        val verified = account is ICloudAccountState.SignedIn && account.status == SessionStatus.VERIFIED
        if (decision != BackgroundSessionDecision.RUN || !verified) {
            return if (decision == BackgroundSessionDecision.RETRY) Result.retry() else Result.success()
        }
        return runCatching {
            val repository = app.graph.galleryRepository
            repository.refresh(includeDeviceMedia = false)
            announce(applicationContext, store, repository)
        }.fold({ Result.success() }, { if (it is CancellationException) throw it else Result.retry() })
    }
}

/** Posts what is new since the last look, and records it as announced. */
internal suspend fun announce(context: Context, store: SharedActivityStore, repository: GalleryRepository) {
    val collections = repository.observeCollections().first()
    val current = SharedAlbumMark.of(collections, repository.observeGallery().first())
    val names = collections.associate { it.id to it.name }
    val invitations: List<SharedInvitation> = repository.pendingSharedInvitations()
    val ledger = store.update { it.observe(current).observeInvitations(invitations.map(SharedInvitation::id).toSet()) }
    val news = ledger.news(current, names)
    val fresh = invitations.filter { it.id in ledger.newInvitations }
    if (!ledger.notificationsEnabled || !canNotify(context)) return
    postSharedActivity(context, news, fresh)
    store.update { it.announce(news, current).announceInvitations(fresh.map(SharedInvitation::id).toSet()) }
}

internal fun canNotify(context: Context): Boolean {
    val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
}

@Suppress("MissingPermission") // canNotify checked the permission.
private fun postSharedActivity(context: Context, news: List<SharedAlbumNews>, invitations: List<SharedInvitation>) {
    if (news.isEmpty() && invitations.isEmpty()) return
    val manager = NotificationManagerCompat.from(context)
    val channel = NotificationChannel(SHARED_CHANNEL, context.getString(R.string.shared_notification_channel), NotificationManager.IMPORTANCE_DEFAULT)
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun builder() = NotificationCompat.Builder(context, SHARED_CHANNEL).setSmallIcon(R.drawable.ic_notification_shared)
        .setContentIntent(open).setAutoCancel(true).setGroup(SHARED_GROUP).setCategory(NotificationCompat.CATEGORY_SOCIAL)
    val resources = context.resources
    if (invitations.isNotEmpty()) manager.notify(SHARED_INVITATION_ID, builder()
        .setContentTitle(resources.getQuantityString(R.plurals.shared_notification_invitations, invitations.size, invitations.size))
        .setContentText(invitations.joinToString(", ") { it.name })
        .build())
    news.forEach { item ->
        manager.notify(item.albumId, SHARED_ALBUM_ID, builder()
            .setContentTitle(context.getString(R.string.shared_notification_new_photos, item.name))
            .setContentText(resources.getQuantityString(R.plurals.shared_notification_new_items, item.newItems, item.newItems))
            .build())
    }
    // Android bundles a group only once it has a summary.
    if (news.size + (if (invitations.isEmpty()) 0 else 1) > 1) manager.notify(SHARED_SUMMARY_ID, builder()
        .setContentTitle(context.getString(R.string.shared_notification_channel))
        .setGroupSummary(true)
        .build())
}
