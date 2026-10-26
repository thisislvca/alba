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
import me.lvca.alba.engine.model.BackupProgress
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.DeviceFolder
import me.lvca.alba.engine.model.FreeUpSpaceCandidates
import me.lvca.alba.engine.model.ICloudPhotoProtocol
import me.lvca.alba.engine.model.MaintenanceWakeup
import me.lvca.alba.engine.model.PhotoCompanion
import me.lvca.alba.engine.model.ReclaimProposalId
import me.lvca.alba.engine.model.SyncTicket
import me.lvca.alba.engine.model.SystemConsentCallback
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
) : PhotoCompanion, MaintenanceWakeup {
    private val accountDao = database.accountDao()
    private val transferDao = database.transferDao()
    private val trashDao = database.trashDao()
    private val maintenanceGate = Mutex()

    private val stages = UploadStages(database, staging, now)
    private val accounts = AccountBindingSync(database, protocol, scheduler, stages, now)
    private val authority = AutomaticBackupAuthority(database, originals, automaticBackupPolicy)
    private val intake = TransferIntake(database, originals, staging, scheduler, accounts, authority, now)
    private val invoker = UploadInvoker(database, staging, protocol, scheduler, accounts, authority, now)
    private val reconciler = Reconciler(database, protocol, authority, stages, now)
    private val enrollment = BackupEnrollment(database, originals, scheduler, now, automaticBackupPolicy, accounts, stages)
    private val trash = VerifiedPhotoTrash(database, originals, protocol, scheduler, now, accounts)

    override fun observeBackup() = enrollment.observeBackup()
    override suspend fun beginBackupEnrollment(scope: BackupScope) = enrollment.beginBackupEnrollment(scope)
    override suspend fun acceptBackupDisclosure(draft: BackupDraftId) = enrollment.acceptBackupDisclosure(draft)
    override suspend fun finishBackupEnrollment(draft: BackupDraftId) = enrollment.finishBackupEnrollment(draft)
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
            val slot = transferDao.findWriteSlot(account.accountId)
            if (slot != null) {
                val attempt = transferDao.findAttempt(slot.attemptId) ?: return
                val transfer = transferDao.findTransfer(attempt.transferId) ?: return
                if (transfer.state == TransferState.SENDING) throw java.io.IOException("Upload interrupted; waiting for the transfer runner to retry")
                if (transfer.state !in RECONCILABLE_STATES) return
                // Verification may temporarily hold the write slot. Keep the platform
                // job alive for the queued manual files without replaying that upload.
                kotlinx.coroutines.delay(((transfer.reconciliationDueAtEpochMillis ?: now()) - now()).coerceIn(1_000L, 15_000L))
            } else if (summary.invokedTransfers == 0) {
                throw java.io.IOException("Could not start the queued upload")
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
                check(transferDao.releaseWriteSlot(binding.accountId, attemptId) == 1) {
                    "The uncertain upload no longer owns the write queue"
                }
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
        val stagedAutomatic = if (allowAutomatic && transferDao.findWriteSlot(binding.accountId) == null) {
            intake.discoverAndStageAutomatic(binding)
        } else {
            0
        }
        val invoked = invoker.invokeOne(binding, allowManual, allowAutomatic)
        if (invoked > 0) reconciled += reconciler.reconcileOne(binding)
        val dueAt = transferDao.earliestReconciliationDue(binding.accountId, now(), authority.mayUseNetwork())
        val ready = transferDao.findWriteSlot(binding.accountId) == null && !invoker.isStoragePaused(binding.accountId) &&
            transferDao.countRunnableReady(
                binding.accountId,
                allowAutomaticPhotos = authority.canRun(binding, video = false),
                allowAutomaticVideos = authority.canRun(binding, video = true),
                allowManual = false,
            ) > 0
        val moreWork = dueAt != null || ready
        if (ready) scheduler.scheduleSoon()
        else if (dueAt != null) scheduler.scheduleAt(dueAt)
        WakeSummary(recovered, reconciled, stagedAutomatic, invoked, moreWork)
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
        override suspend fun freeUpSpaceCandidates(): FreeUpSpaceCandidates = trash.freeUpSpaceCandidates()

        override suspend fun retryTransfer(transfer: TransferId): UploadTicket = intake.retry(transfer.value).also {
            runSuspendCatching { scheduler.scheduleSoon() }
        }

        override suspend fun retryAllTransfers(): Int {
            val binding = accounts.requireActive()
            return intake.retryAll(binding).also { if (it > 0) runSuspendCatching { scheduler.scheduleSoon() } }
        }

        override suspend fun backupProgress(): BackupProgress =
            accounts.synchronize()?.let { intake.backupProgress(it) } ?: BackupProgress(0, 0)

        override suspend fun deviceFolders(): List<DeviceFolder> = originals.deviceFolders()

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
    )
}
