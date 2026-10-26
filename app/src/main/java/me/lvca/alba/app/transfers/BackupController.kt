package me.lvca.alba.app.transfers

import android.Manifest
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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

/** "Free up space" items not yet checked in this run, saved so a run can be offered again after recreation. */
private const val FREE_UP_REMAINING = "freeUpRemaining"

/** The trash attempt of the free-up chunk Android is asking about; other trash answers never touch the run. */
private const val FREE_UP_ATTEMPT = "freeUpAttempt"

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
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val progress = MutableStateFlow<BackupProgress?>(null)
    private val folders = MutableStateFlow<List<DeviceFolder>?>(null)
    private val freeUp = MutableStateFlow(FreeUpSpaceState())
    private var freeUpJob: Job? = null

    /** A free-up run started in this process. A run left from before a restart is offered, never resumed on its own. */
    private var runActive = false

    val extras: Flow<BackupExtras> = combine(preferences.settings, progress, folders, freeUp, ::BackupExtras)

    init {
        // A run left by a killed process, not waiting for Android's answer: ask before continuing it.
        val left = savedState.get<ArrayList<String>>(FREE_UP_REMAINING).orEmpty()
        if (left.isNotEmpty() && savedState.get<String>(FREE_UP_ATTEMPT) == null) freeUp.update { it.copy(leftover = left.size) }
    }

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
        runActive = true
        freeUp.update { it.copy(checking = true, checked = 0, total = ids.size, awaitingConsent = false, leftover = 0) }
        nextFreeUpChunk()
    }

    /**
     * Android answered trash request [attemptId]. Only the free-up chunk's own answer moves the run: an approval
     * continues with the next chunk when the run started in this process, and is offered again otherwise; anything else
     * stops it. Any other trash answer just refreshes what can be freed.
     */
    fun trashAnswered(attemptId: String, approved: Boolean) {
        if (attemptId != savedState.get<String>(FREE_UP_ATTEMPT)) return loadFreeUpSpace()
        savedState.remove<String>(FREE_UP_ATTEMPT)
        val remaining = savedState.get<ArrayList<String>>(FREE_UP_REMAINING).orEmpty()
        when {
            !approved || remaining.isEmpty() -> finishFreeUp()
            runActive -> nextFreeUpChunk()
            else -> freeUp.update { it.copy(checking = false, awaitingConsent = false, leftover = remaining.size) }
        }
    }

    /** The user chose to continue a run left from before a restart. */
    fun continueFreeUp() {
        val remaining = savedState.get<ArrayList<String>>(FREE_UP_REMAINING).orEmpty()
        if (remaining.isEmpty() || isDemo()) return finishFreeUp()
        runActive = true
        freeUp.update { it.copy(checking = true, checked = 0, total = remaining.size, leftover = 0) }
        nextFreeUpChunk()
    }

    /** Stops the run. Nothing leaves the phone without Android's own request, so stopping here removes nothing. */
    fun cancelFreeUp() {
        freeUpJob?.cancel()
        finishFreeUp()
    }

    private fun finishFreeUp() {
        runActive = false
        savedState.remove<ArrayList<String>>(FREE_UP_REMAINING)
        savedState.remove<String>(FREE_UP_ATTEMPT)
        freeUp.update { it.copy(checking = false, awaitingConsent = false, leftover = 0) }
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
                val proposal = withContext(io) {
                    photos.prepareTrash(remaining.toSet(), allowPartial = true) { checked, _ ->
                        freeUp.update { it.copy(checked = offset + checked) }
                    }
                }
                // iCloud out of reach: the rest would fail too. This chunk's verified items may still go, then stop.
                val unreachable = StoredMessage.COULD_NOT_REACH_ICLOUD_TO_CHECK.code in proposal.refusals.values
                savedState[FREE_UP_REMAINING] = if (unreachable) ArrayList() else ArrayList(proposal.unchecked)
                reportLeftOut(proposal.refusals.values, notChecked = if (unreachable) proposal.unchecked.size else 0)
                if (proposal.blockedReason != null) {
                    if (proposal.refusals.isEmpty()) messages.show(localizedStoredMessage(proposal.blockedReason))
                    return@launch if (unreachable || proposal.unchecked.isEmpty()) finishFreeUp() else nextFreeUpChunk()
                }
                val handoff = withContext(io) { photos.beginTrash(proposal.id) }
                val changed = proposal.eligibleMediaIds.size - handoff.mediaIds.size
                if (handoff.mediaIds.isNotEmpty() && changed > 0) {
                    messages.show(UiMessage.quantity(R.plurals.free_up_space_changed, changed))
                }
                // Saved, so Android's answer still reaches the attempt after the activity or process is recreated.
                savedState[TRASH_ATTEMPT_KEY] = handoff.attemptId.value
                savedState[FREE_UP_ATTEMPT] = handoff.attemptId.value
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

    /**
     * Says why items stay: iCloud could not be reached, which also stops the run with [notChecked] items unchecked;
     * the phone file could not be read; or the copies no longer match.
     */
    private fun reportLeftOut(refusals: Collection<String>, notChecked: Int) {
        val unreachable = refusals.count { it == StoredMessage.COULD_NOT_REACH_ICLOUD_TO_CHECK.code }
        val unreadable = refusals.count { it == StoredMessage.COULD_NOT_READ_THIS_FILE_ON_THE_PHONE.code }
        val mismatched = refusals.size - unreachable - unreadable
        when {
            unreachable > 0 -> messages.show(UiMessage.quantity(R.plurals.free_up_space_stopped_unreachable, unreachable + notChecked))
            unreadable > 0 -> messages.show(UiMessage.quantity(R.plurals.free_up_space_unreadable, unreadable))
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
