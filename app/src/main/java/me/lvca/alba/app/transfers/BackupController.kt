package me.lvca.alba.app.transfers

import android.Manifest
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import me.lvca.alba.engine.model.StoredMessage

/** Saved so Android's answer to a verified trash request reaches its attempt after recreation. */
internal const val TRASH_ATTEMPT_KEY = "verifiedTrashAttempt"

/** "Free up space" items not yet checked in this run, saved so a run continues after recreation. */
private const val FREE_UP_REMAINING = "freeUpRemaining"

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
    private var freeUpJob: Job? = null

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
     * Starts "Free up space" for [mediaIds] unless the user chose to wait for Wi-Fi and the phone is on mobile data:
     * the check downloads every original from iCloud.
     */
    fun startFreeUpSpace(mediaIds: List<String>, onMobileData: Boolean) {
        if (onMobileData && preferences.manualTransfersWaitForWifi) messages.show(R.string.free_up_space_needs_wifi)
        else freeUpSpace(mediaIds)
    }

    /**
     * Re-checks [mediaIds] against freshly read phone and iCloud bytes, then asks Android to move the items that still
     * match to its trash, one chunk at a time: each chunk is checked and handed to Android inside the consent window,
     * so no check is ever older than the window when the user is asked. Items that no longer match stay on the phone.
     * At most [PHONE_TRASH_LIMIT] go in one run.
     */
    fun freeUpSpace(mediaIds: List<String>) {
        if (isDemo() || mediaIds.isEmpty() || freeUp.value.checking) return
        val ids = mediaIds.distinct().take(PHONE_TRASH_LIMIT)
        savedState[FREE_UP_REMAINING] = ArrayList(ids)
        freeUp.update { it.copy(checking = true, checked = 0, total = ids.size, awaitingConsent = false) }
        nextFreeUpChunk()
    }

    /** Android answered for a chunk: an approval continues with the next chunk, anything else stops the run. */
    fun freeUpChunkAnswered(approved: Boolean) {
        val remaining = savedState.get<ArrayList<String>>(FREE_UP_REMAINING).orEmpty()
        if (approved && remaining.isNotEmpty()) nextFreeUpChunk() else finishFreeUp()
    }

    /** Stops the run. Nothing leaves the phone without Android's own request, so stopping here removes nothing. */
    fun cancelFreeUp() {
        freeUpJob?.cancel()
        finishFreeUp()
    }

    private fun finishFreeUp() {
        savedState.remove<ArrayList<String>>(FREE_UP_REMAINING)
        freeUp.update { it.copy(checking = false, awaitingConsent = false) }
        loadFreeUpSpace()
    }

    private fun nextFreeUpChunk() {
        val remaining = savedState.get<ArrayList<String>>(FREE_UP_REMAINING).orEmpty()
        if (remaining.isEmpty()) return finishFreeUp()
        // After recreation the counts are gone; the remaining items are the whole run from here.
        val total = freeUp.value.total.takeIf { it >= remaining.size } ?: remaining.size
        val offset = total - remaining.size
        freeUp.update { it.copy(checking = true, total = total, checked = offset, awaitingConsent = false) }
        freeUpJob = scope.launch {
            try {
                val proposal = withContext(Dispatchers.IO) {
                    photos.prepareTrash(remaining.toSet(), allowPartial = true) { checked, _ ->
                        freeUp.update { it.copy(checked = offset + checked) }
                    }
                }
                savedState[FREE_UP_REMAINING] = ArrayList(proposal.unchecked)
                reportLeftOut(proposal.refusals.values)
                if (proposal.blockedReason != null) {
                    if (proposal.refusals.isEmpty()) messages.show(localizedStoredMessage(proposal.blockedReason))
                    // Nothing in this chunk matched. When iCloud is out of reach the rest would fail too, so stop.
                    val unreachable = StoredMessage.COULD_NOT_REACH_ICLOUD_TO_CHECK.code in proposal.refusals.values
                    return@launch if (unreachable || proposal.unchecked.isEmpty()) finishFreeUp() else nextFreeUpChunk()
                }
                val handoff = withContext(Dispatchers.IO) { photos.beginTrash(proposal.id) }
                val changed = proposal.eligibleMediaIds.size - handoff.mediaIds.size
                if (handoff.mediaIds.isNotEmpty() && changed > 0) {
                    messages.show(UiMessage.quantity(R.plurals.free_up_space_changed, changed))
                }
                // Saved, so Android's answer still reaches the attempt after the activity or process is recreated.
                savedState[TRASH_ATTEMPT_KEY] = handoff.attemptId.value
                freeUp.update { it.copy(awaitingConsent = true) }
                effects.send(AlbaEffect.ConfirmVerifiedTrash(handoff.intentSender))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expected: Exception) {
                messages.show(expected.toUserMessage(R.string.message_could_not_prepare_this_photo_for_android_trash))
                finishFreeUp()
            }
        }
    }

    /** Says why items stay: iCloud could not be reached, or the copies no longer match. */
    private fun reportLeftOut(refusals: Collection<String>) {
        val unreachable = refusals.count { it == StoredMessage.COULD_NOT_REACH_ICLOUD_TO_CHECK.code }
        val mismatched = refusals.size - unreachable
        when {
            unreachable > 0 -> messages.show(UiMessage.quantity(R.plurals.free_up_space_unreachable, unreachable))
            mismatched > 0 -> messages.show(UiMessage.quantity(R.plurals.free_up_space_left_out, mismatched))
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
