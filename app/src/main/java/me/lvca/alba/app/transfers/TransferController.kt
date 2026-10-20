package me.lvca.alba.app.transfers

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import me.lvca.alba.app.AlbaEffect
import me.lvca.alba.app.R
import me.lvca.alba.app.actions.MediaAction
import me.lvca.alba.app.actions.MediaActionKey
import me.lvca.alba.app.actions.MediaActionRunner
import me.lvca.alba.app.demo.DemoTransferMode
import me.lvca.alba.app.diagnostics.AlbaDiagnostics
import me.lvca.alba.app.diagnostics.DiagnosticOperation
import me.lvca.alba.app.diagnostics.measure
import me.lvca.alba.app.gallery.GalleryBrowser
import me.lvca.alba.app.gallery.projectTransfers
import me.lvca.alba.app.messages.UserMessages
import me.lvca.alba.app.messages.localizedStoredMessage
import me.lvca.alba.app.messages.toUserMessage
import me.lvca.alba.app.work.BackgroundSessionDecision
import me.lvca.alba.app.work.backgroundSessionDecision
import me.lvca.alba.engine.companion.BatchAction
import me.lvca.alba.engine.companion.GalleryBatchActions
import me.lvca.alba.engine.model.BackupDraftId
import me.lvca.alba.engine.model.BackupSetupEffect
import me.lvca.alba.engine.model.BackupView
import me.lvca.alba.engine.model.BatchItem
import me.lvca.alba.engine.model.GalleryRepository
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.engine.model.PhotoCompanion
import me.lvca.alba.engine.model.SystemConsentCallback
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.TrashAttemptId
import me.lvca.alba.protocol.account.ICloudAccountManager
import me.lvca.alba.protocol.account.ICloudAccountState

/** Batches, uploads and automatic backup, as the UI shows them. */
data class TransferState(
    val batches: List<BatchItem> = emptyList(),
    val queue: List<TransferView> = emptyList(),
    val uploadsByMediaId: Map<String, TransferView> = emptyMap(),
    val backup: BackupView = BackupView(enabled = false),
    val backupSetup: BackupSetup? = null,
)

/** Automatic backup setup the user started: the disclosure to accept, then the permissions Android must grant. */
data class BackupSetup(
    val draftId: BackupDraftId,
    val destinationLabel: String,
    val awaitingPermissions: Boolean = false,
) {
    val demo: Boolean get() = DemoTransferMode.owns(draftId)
}

/**
 * Moves media in and out of the library: batch downloads and removals, uploads, automatic backup and the verified
 * trash handoff. Upload and backup behaviour comes from a [TransferMode], so the demo is a mode rather than a branch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TransferController(
    private val scope: CoroutineScope,
    private val savedState: SavedStateHandle,
    private val accountManager: ICloudAccountManager,
    private val photos: PhotoCompanion,
    private val repository: GalleryRepository,
    private val batches: GalleryBatchActions,
    private val scheduler: TransferScheduler,
    private val diagnostics: AlbaDiagnostics,
    private val messages: UserMessages,
    private val runner: MediaActionRunner,
    private val effects: SendChannel<AlbaEffect>,
    private val gallery: GalleryBrowser,
    private val live: LiveTransferMode,
    private val demo: DemoTransferMode,
) {
    private val isDemo = accountManager.state.map { it == ICloudAccountState.Demo }.distinctUntilChanged()
    private val mode: TransferMode get() = if (accountManager.state.value == ICloudAccountState.Demo) demo else live
    private fun modeFor(draftId: BackupDraftId): TransferMode = if (DemoTransferMode.owns(draftId)) demo else live

    private val setup = MutableStateFlow(savedState.get<String>(BACKUP_DRAFT)?.let {
        BackupSetup(BackupDraftId(it), savedState[BACKUP_DESTINATION] ?: "", savedState[BACKUP_AWAITING_PERMISSIONS] ?: false)
    })

    val state: StateFlow<TransferState> = combine(
        batches.observe(),
        accountManager.state,
        isDemo.flatMapLatest { if (it) demo.transfers else live.transfers }.map(::projectTransfers).flowOn(Dispatchers.Default),
        isDemo.flatMapLatest { if (it) demo.backup else live.backup },
        setup,
    ) { batchRows, _, transfers, backup, setup ->
        TransferState(
            batches = batchRows.filter { it.account == batches.activeAccount },
            queue = transfers.queue,
            uploadsByMediaId = transfers.byMediaId,
            backup = backup,
            backupSetup = setup,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), TransferState())

    init {
        scope.launch {
            accountManager.state.collect { if (it != ICloudAccountState.Demo) demo.reset() }
        }
    }

    fun runBatch(action: BatchAction) {
        val selection = gallery.selectedIds
        val ids = gallery.current.items.filter { item ->
            item.id in selection && when (action) {
                BatchAction.KEEP_OFFLINE, BatchAction.REMOVE_CACHE, BatchAction.SAVE_TO_PHONE -> item.origin == MediaOrigin.ICLOUD
                BatchAction.UPLOAD -> item.origin == MediaOrigin.DEVICE
                BatchAction.PICKER_UPLOAD -> false
            }
        }.map { it.id }
        if (ids.isEmpty()) return
        gallery.clearSelection()
        if (action == BatchAction.UPLOAD) {
            val mode = mode
            launchBatchWork { mode.uploadGalleryMedia(ids) }
            return
        }
        launchBatchWork {
            batches.enqueue(action, ids)
            if (action in setOf(BatchAction.KEEP_OFFLINE, BatchAction.SAVE_TO_PHONE)) messages.show(R.string.download_batch_started)
            if (action == BatchAction.REMOVE_CACHE) drainLocalBatches() else scheduler.schedule()
        }
    }

    fun retryBatch(batchId: String) = launchBatchWork {
        batches.retry(batchId)
        drainLocalBatches()
        if (batches.hasPending()) scheduler.schedule()
    }

    fun cancelDownloadBatch(batchId: String) = launchBatchWork { batches.cancelDownload(batchId) }

    fun keepOriginalOffline(mediaId: String) = launchBatchWork {
        batches.enqueue(BatchAction.KEEP_OFFLINE, listOf(mediaId))
        scheduler.schedule()
    }

    /** Cancels keeping the open photo's original offline. */
    fun cancelOfflineDownload() {
        val id = gallery.selectedMediaId.value ?: return
        val pending = state.value.batches
            .filter { it.mediaId == id && it.action == BatchAction.KEEP_OFFLINE.name && it.status.isPending }
            .map { it.batchId }.distinct()
        launchBatchWork { pending.forEach { batches.cancelDownload(it) } }
    }

    fun removeCachedOriginal(mediaId: String) {
        runner.start(MediaActionKey(MediaAction.REMOVE_OFFLINE_COPY, mediaId), R.string.message_could_not_remove_the_offline_copy) {
            repository.removeCachedOriginal(mediaId)
        }
    }

    fun requestUpload(mediaId: String) {
        val mode = mode
        launchBatchWork { mode.uploadGalleryMedia(listOf(mediaId)) }
    }

    fun uploadPicked(uris: List<Uri>) {
        val mode = mode
        launchBatchWork { mode.uploadPicked(uris) }
    }

    /** Imports the bundled sample photo into the demo library. */
    fun uploadDemoSample() {
        if (mode === demo) launchBatchWork { demo.importSample() }
    }

    fun beginAutomaticBackup() {
        val mode = mode
        scope.launch {
            try {
                val draft = mode.beginBackup()
                saveSetup(BackupSetup(draft.id, draft.destinationLabel))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                messages.show(error.toUserMessage(R.string.message_could_not_start_backup_setup))
            }
        }
    }

    /**
     * Accepts the backup disclosure. Runs in the ViewModel's scope, so a rotation cannot cancel it after the companion
     * recorded the acceptance; a permission request reaches the UI as an [AlbaEffect].
     */
    fun acceptBackupDisclosure() {
        val current = currentSetup() ?: return
        if (current.awaitingPermissions) return
        scope.launch {
            val effect = try {
                modeFor(current.draftId).acceptBackup(current.draftId)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                clearSetup()
                messages.show(error.toUserMessage(R.string.message_could_not_enable_backup))
                return@launch
            }
            when (effect) {
                is BackupSetupEffect.RequestMediaPermissions -> if (effect.permissions.isEmpty()) {
                    clearSetup()
                    finishBackup(current.draftId)
                } else if (currentSetup()?.draftId == current.draftId) {
                    // Only when the user has not dismissed the disclosure while acceptance was in flight.
                    saveSetup(current.copy(awaitingPermissions = true))
                    effects.send(AlbaEffect.RequestBackupPermissions(effect.permissions))
                }
                is BackupSetupEffect.Refused -> clearSetup()
            }
        }
    }

    /** Android answered the backup permission request; backup is enabled for whatever access it granted. */
    fun backupPermissionsAnswered() {
        val current = currentSetup() ?: return
        clearSetup()
        finishBackup(current.draftId)
    }

    fun dismissBackupSetup() = clearSetup()

    fun disableAutomaticBackup() {
        val mode = mode
        scope.launch {
            try { mode.disableBackup() } catch (error: Exception) {
                if (error is CancellationException) throw error
                messages.show(error.toUserMessage(R.string.message_could_not_disable_backup))
            }
        }
    }

    fun continuePastUnresolved(transferId: TransferId) {
        val mode = mode
        scope.launch {
            try { mode.continuePastUnresolved(transferId) } catch (error: Throwable) {
                if (error is CancellationException) throw error
                messages.show(error.toUserMessage(R.string.message_could_not_continue_the_upload_queue))
            }
        }
    }

    /** Re-checks a verified phone original, then asks Android to confirm moving it to the trash. */
    fun requestVerifiedTrash(mediaId: String) {
        runner.start(MediaActionKey(MediaAction.VERIFIED_TRASH, mediaId), R.string.message_could_not_prepare_this_photo_for_android_trash) {
            val proposal = photos.prepareTrash(setOf(mediaId))
            proposal.blockedReason?.let { reason ->
                messages.show(localizedStoredMessage(reason))
                return@start
            }
            val handoff = photos.beginTrash(proposal.id)
            // Saved, so Android's answer still reaches the attempt after the activity or process is recreated.
            savedState[TRASH_ATTEMPT] = handoff.attemptId.value
            effects.send(AlbaEffect.ConfirmVerifiedTrash(handoff.intentSender))
        }
    }

    /** Android's answer to the verified trash request. Ignored when no request is pending. */
    fun verifiedTrashAnswered(result: SystemConsentCallback) {
        val attempt = savedState.get<String>(TRASH_ATTEMPT) ?: return
        savedState[TRASH_ATTEMPT] = null
        scope.launch {
            runCatching { photos.recordTrashResult(TrashAttemptId(attempt), result) }
                .onSuccess {
                    messages.show(if (result == SystemConsentCallback.APPROVED) {
                        R.string.message_android_approved_the_trash_request_alba_is_checking_the_phone_item_its_icloud_copy_remains_veri
                    } else {
                        R.string.message_nothing_was_removed_from_this_phone
                    })
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    messages.show(error.toUserMessage(R.string.message_could_not_confirm_android_s_trash_result))
                }
        }
    }

    /** Finishes local batch work left from an earlier run and schedules what needs the network. */
    fun resumePendingWork() = launchBatchWork {
        drainLocalBatches()
        schedulePendingTransfers()
    }

    fun schedulePendingInBackground() = launchBatchWork { schedulePendingTransfers() }

    /** Stops running batches before the account changes. */
    suspend fun cancelActiveBatches() = batches.cancelActive()

    suspend fun cancelOfflineDownloads() = batches.cancelOfflineDownloads()

    private suspend fun drainLocalBatches() {
        while (batches.runPending(localOnly = true)) yield()
    }

    private suspend fun schedulePendingTransfers() {
        if (backgroundSessionDecision(accountManager.state.value, accountManager.session.value != null) == BackgroundSessionDecision.RUN &&
            (batches.hasPending() || photos.hasPendingUserUploads())) scheduler.schedule()
    }

    private fun launchBatchWork(action: suspend () -> Unit) {
        scope.launch {
            try {
                diagnostics.measure(DiagnosticOperation.BATCH_ACTION) { withContext(Dispatchers.IO) { action() } }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                messages.show(error.toUserMessage(R.string.message_could_not_finish_pending_photo_work))
            }
        }
    }

    private fun finishBackup(draftId: BackupDraftId) {
        val mode = modeFor(draftId)
        scope.launch {
            try { mode.finishBackup(draftId) } catch (error: Exception) {
                if (error is CancellationException) throw error
                messages.show(error.toUserMessage(R.string.message_could_not_enable_backup))
            }
        }
    }

    private fun currentSetup(): BackupSetup? = setup.value

    /** Kept in saved state, so the disclosure and a pending permission answer survive process death. */
    private fun saveSetup(value: BackupSetup) {
        savedState[BACKUP_DRAFT] = value.draftId.value
        savedState[BACKUP_DESTINATION] = value.destinationLabel
        savedState[BACKUP_AWAITING_PERMISSIONS] = value.awaitingPermissions
        setup.value = value
    }

    private fun clearSetup() {
        savedState.remove<String>(BACKUP_DRAFT)
        savedState.remove<String>(BACKUP_DESTINATION)
        savedState.remove<Boolean>(BACKUP_AWAITING_PERMISSIONS)
        setup.value = null
    }

    private companion object {
        const val TRASH_ATTEMPT = "verifiedTrashAttempt"
        const val BACKUP_DRAFT = "backupDraft"
        const val BACKUP_DESTINATION = "backupDestination"
        const val BACKUP_AWAITING_PERMISSIONS = "backupAwaitingPermissions"
    }
}
