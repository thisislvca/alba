package me.lvca.alba.engine.companion

import androidx.room.withTransaction
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.lvca.alba.engine.cache.UploadStagingStore
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.TransferProjection
import me.lvca.alba.engine.database.TransferState
import me.lvca.alba.engine.model.AccountChangeResult
import me.lvca.alba.engine.model.AutomaticBackupPolicy
import me.lvca.alba.engine.model.BackupChange
import me.lvca.alba.engine.model.BackupControls
import me.lvca.alba.engine.model.BackupDraftId
import me.lvca.alba.engine.model.BackupLibrarySummary
import me.lvca.alba.engine.model.BackupProgress
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.CloudLibraryIndex
import me.lvca.alba.engine.model.DeviceFolder
import me.lvca.alba.engine.model.FreeUpSpaceCandidates
import me.lvca.alba.engine.model.ICloudPhotoProtocol
import me.lvca.alba.engine.model.MaintenanceWakeup
import me.lvca.alba.engine.model.PhotoCompanion
import me.lvca.alba.engine.model.ProtocolStatusGate
import me.lvca.alba.engine.model.ReclaimProposalId
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.SyncTicket
import me.lvca.alba.engine.model.SystemConsentCallback
import me.lvca.alba.engine.model.TransferFailureReason
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.model.TransferProgress
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.TransferViewState
import me.lvca.alba.engine.model.TrashAttemptId
import me.lvca.alba.engine.model.UploadTicket
import me.lvca.alba.engine.model.UserSelectedPhoto
import me.lvca.alba.engine.model.WakeReason
import me.lvca.alba.engine.model.WakeSummary
import me.lvca.alba.engine.model.WakeupScheduler
import me.lvca.alba.engine.source.ExactOriginalSource
import java.util.UUID

/** How long the user's transfer job waits, at least and at most, for an upload's retry backoff before looking again. */
private const val MIN_BACKOFF_WAIT_MILLIS = 1_000L
private const val MAX_BACKOFF_WAIT_MILLIS = 60_000L

/**
 * The photo companion: wires account binding, intake, upload, reconciliation, backup and trash, and runs them
 * in order on each maintenance wake.
 */
class DefaultPhotoCompanion(
    private val database: AlbaDatabase,
    private val originals: ExactOriginalSource,
    staging: UploadStagingStore,
    private val protocol: ICloudPhotoProtocol,
    private val scheduler: WakeupScheduler,
    private val now: () -> Long = System::currentTimeMillis,
    private val automaticBackupPolicy: AutomaticBackupPolicy = AutomaticBackupPolicy { false },
    /** A clock that never moves back; the trash consent window is measured on it. */
    elapsedRealtime: () -> Long = android.os.SystemClock::elapsedRealtime,
    /** The synced iCloud library, where automatic backup looks for each item before uploading it. */
    cloudLibrary: CloudLibraryIndex = CloudLibraryIndex.NONE,
    /** Alba's remote protocol status, which can pause uploads while an iCloud change is investigated. */
    protocolStatus: ProtocolStatusGate = ProtocolStatusGate.Open,
) : PhotoCompanion, MaintenanceWakeup {
    private val accountDao = database.accountDao()
    private val transferDao = database.transferDao()
    private val trashDao = database.trashDao()
    private val maintenanceGate = Mutex()

    private val stages = UploadStages(database, staging, now)
    private val accounts = AccountBindingSync(database, protocol, scheduler, stages, now)
    private val authority = AutomaticBackupAuthority(database, originals, automaticBackupPolicy)
    private val intake = TransferIntake(database, originals, staging, scheduler, accounts, authority, now)
    private val invoker = UploadInvoker(database, UploadOriginals(staging, InPlaceOriginals(originals), intake::restage), protocol,
        scheduler, accounts, authority, now, stages, ExistingCopyCheck(protocol, cloudLibrary, now), protocolStatus)
    private val reconciler = Reconciler(database, originals, protocol, authority, stages, now)
    private val healer = UncertainHealer(database, protocol, cloudLibrary, stages, now)
    private val enrollment = BackupEnrollment(database, originals, scheduler, now, automaticBackupPolicy, accounts, stages, protocolStatus)
    private val trash = VerifiedPhotoTrash(database, originals, protocol, scheduler, now, accounts, elapsedRealtime)

    override fun observeBackup() = enrollment.observeBackup()
    override suspend fun beginBackupEnrollment(scope: BackupScope) = enrollment.beginBackupEnrollment(scope)
    override suspend fun acceptBackupDisclosure(draft: BackupDraftId) = enrollment.acceptBackupDisclosure(draft)
    override suspend fun finishBackupEnrollment(draft: BackupDraftId, includeExisting: Boolean) =
        enrollment.finishBackupEnrollment(draft, includeExisting)
    override suspend fun changeBackup(change: BackupChange) = enrollment.changeBackup(change)

    override fun observeTransfers(): Flow<List<TransferView>> = transferDao.observeTransfers().map { rows ->
        rows.map(::toTransferView)
    }

    override suspend fun uploadSelected(photo: UserSelectedPhoto): UploadTicket = intake.uploadSelected(photo)

    override suspend fun uploadDeviceMedia(mediaId: String): UploadTicket = intake.uploadDeviceMedia(mediaId)

    override suspend fun syncNow(): SyncTicket {
        val ticket = SyncTicket(UUID.randomUUID().toString())
        wake(WakeReason.USER_REQUESTED)
        return ticket
    }

    override suspend fun hasPendingUserUploads(): Boolean =
        accountDao.findActiveAccount()?.let { transferDao.countPendingManualTransfers(it.accountId) > 0 } ?: false

    override suspend fun runUserUploads() {
        while (true) {
            val summary = wake(WakeReason.USER_TRANSFER)
            val account = accountDao.findActiveAccount() ?: return
            if (transferDao.countPendingManualTransfers(account.accountId) == 0) return
            // iCloud is full: stop here rather than retrying; the queue resumes when the user tries again.
            if (invoker.isStoragePaused(account.accountId)) return
            // Uploads are paused remotely: the queue stays as it is and continues on the next run after the pause.
            if (invoker.isProtocolPaused()) return
            val held = transferDao.findWriteSlots(account.accountId).mapNotNull { slot ->
                transferDao.findAttempt(slot.attemptId)?.let { transferDao.findTransfer(it.transferId) }
            }
            if (held.any { it.state == TransferState.SENDING }) throw java.io.IOException("Upload interrupted; waiting for the transfer runner to retry")
            val checking = held.filter { it.state in RECONCILABLE_STATES }
            if (checking.isNotEmpty() && (held.size >= MAX_UPLOADS_IN_FLIGHT || summary.invokedTransfers == 0)) {
                // Verification may temporarily hold write slots. Keep the platform
                // job alive for the queued manual files without replaying those uploads.
                val due = checking.minOf { it.reconciliationDueAtEpochMillis ?: now() }
                kotlinx.coroutines.delay((due - now()).coerceIn(1_000L, 15_000L))
            } else if (held.isNotEmpty() && checking.isEmpty() && summary.invokedTransfers == 0) {
                return
            } else if (summary.invokedTransfers == 0) {
                // An upload proven not received waits out its backoff; keep the job alive rather than failing it.
                val retryAt = transferDao.earliestRetryDue(account.accountId, now(), allowAutomatic = false)
                    ?: throw java.io.IOException("Could not start the queued upload")
                kotlinx.coroutines.delay((retryAt - now()).coerceIn(MIN_BACKOFF_WAIT_MILLIS, MAX_BACKOFF_WAIT_MILLIS))
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
        }
    }

    override suspend fun cancelUserUploads() = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        invoker.cancelManual()
    }

    override suspend fun continuePastUnresolved(transfer: TransferId) {
        val row = requireNotNull(transferDao.findTransfer(transfer.value)) { "Unknown upload" }
        val attemptId = requireNotNull(row.activeAttemptId) { "This upload has no uncertain attempt" }
        require(row.state == TransferState.AMBIGUOUS || row.state == TransferState.NOT_OBSERVED) {
            "Only a completed uncertain check can be skipped"
        }
        val binding = accounts.requireActive()
        require(row.accountId == binding.accountId) { "This upload belongs to a different iCloud account" }
        accounts.gate(binding.accountId).withLock {
            database.withTransaction {
                val active = requireNotNull(accountDao.findActiveAccount())
                require(active.toBinding() == binding) { "The iCloud account changed" }
                check(transferDao.abandonUnresolved(row.transferId, attemptId, now()) == 1) {
                    "This uncertain upload was already resolved"
                }
                // An uncertain upload gave its write slot back when it became uncertain; one from before schema 25
                // gave it back in the migration. Releasing again is harmless.
                transferDao.releaseWriteSlot(binding.accountId, attemptId)
            }
        }
        transferDao.findStagedSource(row.stagedSourceId)?.let { source ->
            stages.release(source)
        }
        runSuspendCatching { scheduler.scheduleSoon() }
    }

    override suspend fun wake(reason: WakeReason): WakeSummary = maintenanceGate.withLock {
        val allowManual = reason == WakeReason.USER_REQUESTED || reason == WakeReason.USER_TRANSFER
        val allowAutomatic = reason != WakeReason.USER_TRANSFER
        val binding = accounts.synchronize()
        val recovered = invoker.recoverInterrupted(binding, allowManual, allowAutomatic)
        trashDao.recoverTrashHandoffs()
        trash.inspectApprovedTrashAttempts()
        stages.deleteReleasable()
        stages.sweepOrphans()

        if (binding == null || !protocol.isWriteAuthorized()) return@withLock WakeSummary(recovered, 0, 0, 0, false)
        var reconciled = reconciler.reconcileOne(binding)
        // Uploads that ended uncertain are looked for again once the library has been refreshed since the last look.
        // Automatic items wait for a network backup may use, as their uploads did; the user's own go on any network.
        reconciled += healer.healDue(binding) { it.authorityType == AUTHORITY_MANUAL || authority.mayUseNetwork() }
        // Verification only reads, so it continues while uploads are paused remotely; nothing new is sent or staged.
        val uploadsPaused = invoker.isProtocolPaused()

        // Whether another upload could start now: a write slot is free, iCloud has space and uploads are not paused.
        suspend fun mayStartUpload() = !uploadsPaused && invoker.freeSlots(binding) > 0
        // Up to three uploads at once; discovery stages the next items while they upload. Nothing new is staged while
        // no upload could start: it could not upload anyway.
        val round = if (uploadsPaused) {
            UploadInvoker.Round(0, 0)
        } else {
            invoker.runRound(binding, allowManual, allowAutomatic) {
                if (allowAutomatic) intake.discoverAndStageAutomatic(binding) else 0
            }
        }
        val stagedAutomatic = round.staged
        val invoked = round.invoked
        // Each upload just sent is matched by its receipt now, rather than on the next pass.
        repeat(invoked) { reconciled += reconciler.reconcileOne(binding) }
        val dueAt = listOfNotNull(
            transferDao.earliestReconciliationDue(binding.accountId, now(), authority.mayUseNetwork()),
            transferDao.earliestRetryDue(binding.accountId, now(), allowAutomatic = authority.mayBackUp(), allowManual = false),
        ).minOrNull()
        // While paused, queued items wait for the periodic wake rather than asking for another pass at once.
        val ready = mayStartUpload() &&
            transferDao.countRunnableReady(
                binding.accountId,
                now(),
                allowAutomaticPhotos = authority.canRun(binding, video = false),
                allowAutomaticVideos = authority.canRun(binding, video = true),
                allowManual = false,
            ) > 0
        val moreWork = dueAt != null || ready
        if (ready) scheduler.scheduleSoon()
        else if (dueAt != null) scheduler.scheduleAt(dueAt)
        WakeSummary(recovered, reconciled, stagedAutomatic, invoked, moreWork, nextWorkAtEpochMillis = if (ready) now() else dueAt)
    }

    override suspend fun prepareTrash(
        items: Set<String>,
        allowPartial: Boolean,
        onProgress: suspend (checked: Int, total: Int) -> Unit,
    ) = trash.prepareTrash(items, allowPartial, onProgress)

    override suspend fun beginTrash(proposal: ReclaimProposalId) = trash.beginTrash(proposal)
    override suspend fun recordTrashResult(attempt: TrashAttemptId, result: SystemConsentCallback) =
        trash.recordTrashResult(attempt, result)

    override val backup: BackupControls = Controls()

    /** Backup status, settings, retry and free space, over the same intake, trash and journal. */
    private inner class Controls : BackupControls {
        override suspend fun freeUpSpaceCandidates(): FreeUpSpaceCandidates {
            // Before anything is offered for deletion, ambiguous uploads are settled by their bytes where they can be.
            runSuspendCatching { accounts.synchronize()?.let { healer.resolveAllByBytes(it) } }
            return trash.freeUpSpaceCandidates()
        }

        override suspend fun checkUncertain(transfer: TransferId): Boolean {
            val binding = accounts.synchronize() ?: return false
            return healer.resolveByBytes(binding, transfer.value)
        }

        override suspend fun retryTransfer(transfer: TransferId): UploadTicket = intake.retry(transfer.value).also {
            runSuspendCatching { scheduler.scheduleSoon() }
        }

        override suspend fun retryAllTransfers(): Int {
            val binding = accounts.requireActive()
            return intake.retryAll(binding).also { if (it > 0) runSuspendCatching { scheduler.scheduleSoon() } }
        }

        override suspend fun backupProgress(): BackupProgress =
            accounts.synchronize()?.let { intake.backupProgress(it) } ?: BackupProgress(0, 0)

        override suspend fun verifiedSince(sinceEpochMillis: Long): Int =
            accounts.synchronize()?.let { intake.verifiedSince(it, sinceEpochMillis) } ?: 0

        override suspend fun deviceFolders(): List<DeviceFolder> = originals.deviceFolders()

        override suspend fun libraryToBackUp(folders: Set<String>): BackupLibrarySummary? =
            originals.librarySummary(BackupScope.CURRENT, folders)

        override suspend fun backupSettingsChanged() {
            val binding = accounts.synchronize()
            val enabled = binding != null && authority.currentEnrollment(binding) != null
            if (automaticBackupPolicy.settings().paused || !enabled) {
                scheduler.setAutomaticBackupEnabled(false)
                scheduler.cancelRunningBackup()
            } else {
                scheduler.setAutomaticBackupEnabled(true)
                runSuspendCatching { scheduler.scheduleSoon() }
            }
        }

        override suspend fun retryAfterStorageFull() {
            val binding = accounts.requireActive()
            transferDao.clearUploadPause(binding.accountId)
            runSuspendCatching { scheduler.scheduleSoon() }
        }

        override suspend fun userUploadProgress(sinceEpochMillis: Long): TransferProgress {
            val account = accountDao.findActiveAccount() ?: return TransferProgress(0, 0)
            val total = transferDao.countManualInRun(account.accountId, sinceEpochMillis)
            val pending = transferDao.countPendingManualTransfers(account.accountId)
            return TransferProgress(done = (total - pending).coerceAtLeast(0), total = total)
        }
    }

    override suspend fun accountWillChange(): AccountChangeResult = accounts.accountWillChange()

    private fun TransferProjection.toViewState(): TransferViewState = when (state) {
        TransferState.READY -> TransferViewState.WAITING
        TransferState.SENDING -> TransferViewState.UPLOADING
        TransferState.OUTCOME_UNKNOWN, TransferState.RECONCILING, TransferState.READ_FAILURE -> TransferViewState.CHECKING_ICLOUD
        TransferState.VERIFIED -> TransferViewState.VERIFIED
        TransferState.AMBIGUOUS, TransferState.NOT_OBSERVED -> TransferViewState.UNRESOLVED
        TransferState.ABANDONED -> TransferViewState.SKIPPED
        TransferState.NEEDS_SIGN_IN -> TransferViewState.NEEDS_SIGN_IN
        TransferState.UNSUPPORTED -> TransferViewState.UNSUPPORTED
        else -> TransferViewState.NEEDS_ATTENTION
    }

    private fun toTransferView(row: TransferProjection) = TransferView(
        id = TransferId(row.transferId),
        mediaId = row.mediaId,
        displayName = row.displayName,
        state = row.toViewState(),
        byteCount = row.byteCount,
        updatedAtEpochMillis = row.updatedAtEpochMillis,
        verifiedAtEpochMillis = row.verifiedAtEpochMillis,
        message = row.message,
        automatic = row.authorityType == AUTHORITY_AUTOMATIC,
        retryable = row.activeAttemptId == null &&
            (row.state == TransferState.NEEDS_ATTENTION || row.state == TransferState.UNSUPPORTED),
        alreadyInICloud = row.state == TransferState.VERIFIED && row.message == StoredMessage.ALREADY_IN_ICLOUD.code,
        failureReason = row.failureReasonView(),
        nextTryAtEpochMillis = row.notBeforeEpochMillis?.takeIf { row.state == TransferState.READY && row.automaticRetries > 0 },
    )

    /** Why the item has not gone through, by what fixes it; null while nothing is wrong. */
    private fun TransferProjection.failureReasonView(): TransferFailureReason? = when (state) {
        TransferState.READY -> TransferFailureReason.WAITING_TO_RETRY.takeIf { automaticRetries > 0 }
        TransferState.SENDING -> TransferFailureReason.STORAGE_FULL.takeIf { message == StoredMessage.ICLOUD_STORAGE_FULL.code }
        TransferState.AMBIGUOUS, TransferState.NOT_OBSERVED ->
            if (failureReason == FailureReasons.ICLOUD_REJECTED) TransferFailureReason.FORMAT_NOT_SUPPORTED else TransferFailureReason.CHECKING_ICLOUD
        TransferState.UNSUPPORTED -> TransferFailureReason.FORMAT_NOT_SUPPORTED
        TransferState.NEEDS_ATTENTION, TransferState.NEEDS_SIGN_IN -> when {
            failureReason == FailureReasons.ICLOUD_REJECTED -> TransferFailureReason.FORMAT_NOT_SUPPORTED
            failureReason == FailureReasons.FILE_GONE || StoredMessage.decode(message) in FILE_GONE_MESSAGES -> TransferFailureReason.FILE_GONE
            StoredMessage.decode(message) in PHOTO_ACCESS_MESSAGES -> TransferFailureReason.PHOTO_ACCESS
            else -> TransferFailureReason.STOPPED
        }
        else -> null
    }

    private companion object {
        /** Messages that mean the phone file is gone, from before the reason was kept with the transfer. */
        val FILE_GONE_MESSAGES = setOf(
            StoredMessage.THE_STAGED_ORIGINAL_CHANGED_OR_IS_MISSING_SELECT_THE_PHOTO_AGAIN,
            StoredMessage.THIS_PHOTO_IS_NO_LONGER_AVAILABLE_ON_THE_PHONE,
            StoredMessage.THIS_FILE_IS_NO_LONGER_ON_THE_PHONE,
        )

        /** Messages that mean automatic backup lost the photo access it needs. */
        val PHOTO_ACCESS_MESSAGES = setOf(
            StoredMessage.AUTOMATIC_BACKUP_PERMISSION_OR_CONSENT_CHANGED,
            StoredMessage.BACKUP_PAUSED_ALLOW_FULL_PHOTO_AND_MEDIA_LOCATION_ACCESS,
        )
    }
}
