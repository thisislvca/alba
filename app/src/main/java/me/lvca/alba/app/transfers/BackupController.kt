package me.lvca.alba.app.transfers

import android.Manifest
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lvca.alba.app.AlbaEffect
import me.lvca.alba.app.EffectQueue
import me.lvca.alba.app.R
import me.lvca.alba.app.messages.UiMessage
import me.lvca.alba.app.messages.UserMessages
import me.lvca.alba.app.messages.localizedStoredMessage
import me.lvca.alba.app.messages.toUserMessage
import me.lvca.alba.app.platform.BackupPreferences
import me.lvca.alba.app.platform.MobileDataChoice
import me.lvca.alba.app.platform.PHONE_TRASH_LIMIT
import me.lvca.alba.engine.model.BackupProgress
import me.lvca.alba.engine.model.BackupSettings
import me.lvca.alba.engine.model.DeviceFolder
import me.lvca.alba.engine.model.PhotoCompanion

/** Saved so Android's answer to a verified trash request reaches its attempt after recreation. */
internal const val TRASH_ATTEMPT_KEY = "verifiedTrashAttempt"

/**
 * Backup status, settings and "Free up space" for the Backup page, and what Alba asks before a manual transfer:
 * notifications once, and mobile data. The demo has none of it.
 */
internal class BackupController(
    private val scope: CoroutineScope,
    private val savedState: SavedStateHandle,
    private val photos: PhotoCompanion,
    private val preferences: BackupPreferences,
    private val scheduler: TransferScheduler,
    private val messages: UserMessages,
    private val effects: EffectQueue,
    private val isDemo: () -> Boolean,
) {
    private val progress = MutableStateFlow<BackupProgress?>(null)
    private val folders = MutableStateFlow<List<DeviceFolder>?>(null)
    private val freeUp = MutableStateFlow(FreeUpSpaceState())

    val extras: Flow<BackupExtras> = combine(preferences.settings, progress, folders, freeUp, ::BackupExtras)

    /** Counts how much of what backup covers is verified. Cheap enough to run when the Backup page shows. */
    fun refreshBackupStatus() {
        if (isDemo()) return
        scope.launch {
            try {
                progress.value = withContext(Dispatchers.IO) { photos.backup.backupProgress() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expected: Exception) {
                progress.value = null
            }
        }
    }

    fun loadDeviceFolders() {
        scope.launch {
            folders.value = try { withContext(Dispatchers.IO) { photos.backup.deviceFolders() } } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expected: Exception) {
                emptyList()
            }
        }
    }

    /** Saves backup settings and re-plans background work for the networks they allow. */
    fun setBackupSettings(settings: BackupSettings) {
        preferences.update { settings }
        scope.launch {
            try { photos.backup.backupSettingsChanged() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expected: Exception) {
                messages.show(expected.toUserMessage(R.string.message_could_not_change_backup_settings))
            }
            refreshBackupStatus()
        }
    }

    /** iCloud has space again, by the user's word: lift the pause and continue the queue. */
    fun retryAfterStorageFull() {
        if (isDemo()) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    photos.backup.retryAfterStorageFull()
                    if (photos.hasPendingUserUploads()) scheduler.schedule()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expected: Exception) {
                messages.show(expected.toUserMessage(R.string.message_could_not_finish_pending_photo_work))
            }
        }
    }

    /** The choice for manual transfers on mobile data, as remembered. */
    val mobileDataChoice: MobileDataChoice get() = preferences.mobileDataChoice

    /** The user answered "Use mobile data?" for this run, and whether to remember it. */
    fun chooseMobileData(useMobileData: Boolean, remember: Boolean) {
        preferences.manualTransfersWaitForWifi = !useMobileData
        if (remember) preferences.mobileDataChoice = if (useMobileData) MobileDataChoice.USE else MobileDataChoice.WAIT
    }

    /** Whether Alba should explain and ask for notifications before the first manual transfer. */
    fun shouldAskForNotifications(): Boolean = !preferences.notificationsAsked && notificationPermission().isNotEmpty()

    fun notificationsAsked() {
        preferences.notificationsAsked = true
    }

    /** Lists what "Free up space" could remove from the phone. */
    fun loadFreeUpSpace() {
        if (isDemo()) return
        scope.launch {
            freeUp.update { it.copy(loading = true) }
            try {
                val candidates = withContext(Dispatchers.IO) { photos.backup.freeUpSpaceCandidates() }
                freeUp.update { it.copy(candidates = candidates) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expected: Exception) {
                messages.show(expected.toUserMessage(R.string.message_could_not_prepare_this_photo_for_android_trash))
            } finally {
                freeUp.update { it.copy(loading = false) }
            }
        }
    }

    /**
     * Re-checks [mediaIds] against freshly read phone and iCloud bytes as one batch, with progress, then asks Android
     * to move the items that still match to its trash. Items that no longer match stay on the phone. At most
     * [PHONE_TRASH_LIMIT] go in one request.
     */
    fun freeUpSpace(mediaIds: List<String>) {
        if (isDemo() || mediaIds.isEmpty() || freeUp.value.checking) return
        val ids = mediaIds.distinct().take(PHONE_TRASH_LIMIT).toSet()
        scope.launch {
            freeUp.update { it.copy(checking = true, checked = 0, total = ids.size) }
            try {
                val proposal = withContext(Dispatchers.IO) {
                    photos.prepareTrash(ids, allowPartial = true) { checked, _ -> freeUp.update { it.copy(checked = checked) } }
                }
                proposal.blockedReason?.let { reason ->
                    messages.show(localizedStoredMessage(reason))
                    return@launch
                }
                val leftOut = ids.size - proposal.eligibleMediaIds.size
                if (leftOut > 0) messages.show(UiMessage.quantity(R.plurals.free_up_space_left_out, leftOut))
                val handoff = withContext(Dispatchers.IO) { photos.beginTrash(proposal.id) }
                // Saved, so Android's answer still reaches the attempt after the activity or process is recreated.
                savedState[TRASH_ATTEMPT_KEY] = handoff.attemptId.value
                effects.send(AlbaEffect.ConfirmVerifiedTrash(handoff.intentSender))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expected: Exception) {
                messages.show(expected.toUserMessage(R.string.message_could_not_prepare_this_photo_for_android_trash))
            } finally {
                freeUp.update { it.copy(checking = false) }
            }
        }
    }

    /** Stops the user's uploads. One already sent is checked in iCloud, never sent again. */
    fun cancelUploads() {
        if (isDemo()) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { scheduler.cancel() }
                messages.show(R.string.uploads_canceled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expected: Exception) {
                messages.show(expected.toUserMessage(R.string.message_could_not_finish_pending_photo_work))
            }
        }
    }

    /** Notifications are asked for in context, on Android 13 and later, until asked once. */
    fun notificationPermission(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !preferences.notificationsAsked) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else emptyList()
}
