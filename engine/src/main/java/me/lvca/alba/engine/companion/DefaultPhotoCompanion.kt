package me.lvca.alba.engine.companion

import androidx.room.withTransaction
import me.lvca.alba.engine.cache.StagedOriginalUnavailableException
import me.lvca.alba.engine.cache.UploadStagingStore
import me.lvca.alba.engine.database.AccountEntity
import me.lvca.alba.engine.database.AccountWriteSlotEntity
import me.lvca.alba.engine.database.AssetLinkEntity
import me.lvca.alba.engine.database.BackupEnrollmentEntity
import me.lvca.alba.engine.database.LocalAssetEntity
import me.lvca.alba.engine.database.MediaStoreCheckpointEntity
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.ReconciliationMatchEntity
import me.lvca.alba.engine.database.RemoteAssetPairEntity
import me.lvca.alba.engine.database.StagedSourceEntity
import me.lvca.alba.engine.database.TransferEntity
import me.lvca.alba.engine.database.TransferProjection
import me.lvca.alba.engine.database.TransferState
import me.lvca.alba.engine.database.UploadAcceptanceEntity
import me.lvca.alba.engine.database.UploadAttemptEntity
import me.lvca.alba.engine.database.UploadSuppressionEntity
import me.lvca.alba.engine.database.UploadUncertaintyEntity
import me.lvca.alba.engine.database.VerificationProofEntity
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.AccountChangeResult
import me.lvca.alba.engine.model.AutomaticBackupPolicy
import me.lvca.alba.engine.model.BackupChange
import me.lvca.alba.engine.model.BackupDraftId
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.ICloudPhotoProtocol
import me.lvca.alba.engine.model.MaintenanceWakeup
import me.lvca.alba.engine.model.PhotoCompanion
import me.lvca.alba.engine.model.PhotoCompanionException
import me.lvca.alba.engine.model.ReclaimProposalId
import me.lvca.alba.engine.model.RemotePairCandidate
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.SyncTicket
import me.lvca.alba.engine.model.SystemConsentCallback
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.TransferViewState
import me.lvca.alba.engine.model.TrashAttemptId
import me.lvca.alba.engine.model.UploadAcceptance
import me.lvca.alba.engine.model.UploadProcessingStatus
import me.lvca.alba.engine.model.UploadTicket
import me.lvca.alba.engine.model.UserSelectedPhoto
import me.lvca.alba.engine.model.WakeReason
import me.lvca.alba.engine.model.WakeSummary
import me.lvca.alba.engine.model.WakeupScheduler
import me.lvca.alba.engine.source.ExactOriginalSource
import me.lvca.alba.engine.source.LocalDiscoveryCheckpoint
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DefaultPhotoCompanion(
    private val database: AlbaDatabase,
    private val originals: ExactOriginalSource,
    private val staging: UploadStagingStore,
    private val protocol: ICloudPhotoProtocol,
    private val scheduler: WakeupScheduler,
    private val now: () -> Long = System::currentTimeMillis,
    private val automaticBackupPolicy: AutomaticBackupPolicy = AutomaticBackupPolicy { false },
) : PhotoCompanion, MaintenanceWakeup {
    private val dao = database.photoWriteDao()
    private val accountGates = ConcurrentHashMap<String, Mutex>()
    private val maintenanceGate = Mutex()
    private val backup = BackupEnrollment(database, originals, scheduler, now, automaticBackupPolicy,
        ::requireActiveBinding, ::synchronizeAccount, ::accountGate, ::deleteReleasableStages)

    override fun observeBackup() = backup.observeBackup()
    override suspend fun beginBackupEnrollment(scope: BackupScope) = backup.beginBackupEnrollment(scope)
    override suspend fun acceptBackupDisclosure(draft: BackupDraftId) = backup.acceptBackupDisclosure(draft)
    override suspend fun finishBackupEnrollment(draft: BackupDraftId) = backup.finishBackupEnrollment(draft)
    override suspend fun changeBackup(change: BackupChange) = backup.changeBackup(change)

    private val trash = VerifiedPhotoTrash(database, originals, protocol, scheduler, now, ::requireActiveBinding, ::accountGate)

    override fun observeTransfers(): Flow<List<TransferView>> = dao.observeTransfers().map { rows ->
        rows.map(::toTransferView)
    }

    override suspend fun uploadSelected(photo: UserSelectedPhoto): UploadTicket {
        val binding = requireActiveBinding()
        return createTransfer(
            binding = binding,
            authorityType = AUTHORITY_MANUAL,
            authorityId = "selection:${UUID.randomUUID()}",
            enrollment = null,
            mediaId = null,
            contentUri = photo.contentUri,
        ) { output ->
            originals.copySelected(photo.contentUri, photo.displayName, output)
        }
    }

    override suspend fun uploadDeviceMedia(mediaId: String): UploadTicket {
        val item = refuseIfNull(database.catalogDao().findById(mediaId), StoredMessage.UNKNOWN_DEVICE_PHOTO)
        refuseUnless(item.origin == "DEVICE", StoredMessage.ONLY_PHOTOS_ON_THIS_PHONE_CAN_BE_UPLOADED)
        val contentUri = refuseIfNull(item.localUri, StoredMessage.THIS_PHOTO_IS_NO_LONGER_AVAILABLE_ON_THE_PHONE)
        val binding = requireActiveBinding()
        return createTransfer(
            binding = binding,
            authorityType = AUTHORITY_MANUAL,
            authorityId = "device-selection:${UUID.randomUUID()}",
            enrollment = null,
            mediaId = mediaId,
            contentUri = contentUri,
        ) { output ->
            originals.copyMediaStoreItem(contentUri, item.sourceRevision, output)
        }
    }

    override suspend fun syncNow(): SyncTicket {
        val ticket = SyncTicket(UUID.randomUUID().toString())
        wake(WakeReason.USER_REQUESTED)
        return ticket
    }

    override suspend fun hasPendingUserUploads(): Boolean =
        dao.findActiveAccount()?.let { dao.countPendingManualTransfers(it.accountId) > 0 } ?: false

    override suspend fun runUserUploads() {
        while (true) {
            val summary = wake(WakeReason.USER_TRANSFER)
            val account = dao.findActiveAccount() ?: return
            if (dao.countPendingManualTransfers(account.accountId) == 0) return
            val slot = dao.findWriteSlot(account.accountId)
            if (slot != null) {
                val attempt = dao.findAttempt(slot.attemptId) ?: return
                val transfer = dao.findTransfer(attempt.transferId) ?: return
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
        // Do not take the maintenance/account mutex: an upload holds it while
        // awaiting Apple. Commit the stop before cancelling its coroutine.
        database.withTransaction {
            val account = dao.findActiveAccount() ?: return@withTransaction
            dao.cancelReadyManualTransfers(account.accountId, now())
            dao.findInterruptedSending().filter { it.accountId == account.accountId && it.authorityType == AUTHORITY_MANUAL }
                .forEach { transfer ->
                    val attemptId = requireNotNull(transfer.activeAttemptId)
                    dao.insertUncertainty(UploadUncertaintyEntity(attemptId, "USER_CANCELLED", now()))
                    dao.markOutcomeUnknown(transfer.transferId, attemptId, now(), now() + RECONCILIATION_DEADLINE_MILLIS)
                }
        }
        Unit
    }

    override suspend fun continuePastUnresolved(transfer: TransferId) {
        val row = requireNotNull(dao.findTransfer(transfer.value)) { "Unknown upload" }
        val attemptId = requireNotNull(row.activeAttemptId) { "This upload has no uncertain attempt" }
        require(row.state == TransferState.AMBIGUOUS || row.state == TransferState.NOT_OBSERVED) {
            "Only a completed uncertain check can be skipped"
        }
        val binding = requireActiveBinding()
        require(row.accountId == binding.accountId) { "This upload belongs to a different iCloud account" }
        accountGate(binding.accountId).withLock {
            database.withTransaction {
                val active = requireNotNull(dao.findActiveAccount())
                require(active.toBinding() == binding) { "The iCloud account changed" }
                check(dao.abandonUnresolved(row.transferId, attemptId, now()) == 1) {
                    "This uncertain upload was already resolved"
                }
                check(dao.releaseWriteSlot(binding.accountId, attemptId) == 1) {
                    "The uncertain upload no longer owns the write queue"
                }
            }
        }
        dao.findStagedSource(row.stagedSourceId)?.let { source ->
            releaseStage(source)
        }
        runSuspendCatching { scheduler.scheduleSoon() }
    }

    override suspend fun wake(reason: WakeReason): WakeSummary = maintenanceGate.withLock {
        val allowManual = reason == WakeReason.USER_REQUESTED || reason == WakeReason.USER_TRANSFER
        val allowAutomatic = reason != WakeReason.USER_TRANSFER
        val binding = synchronizeAccount()
        val recovered = recoverInterruptedInvocations(binding, allowManual, allowAutomatic)
        dao.recoverTrashHandoffs()
        trash.inspectApprovedTrashAttempts()
        deleteReleasableStages()
        sweepOrphanStages()

        if (binding == null || !protocol.isWriteAuthorized()) return@withLock WakeSummary(recovered, 0, 0, 0, false)
        var reconciled = reconcileOne(binding)
        val stagedAutomatic = if (allowAutomatic && dao.findWriteSlot(binding.accountId) == null) {
            discoverAndStageAutomatic(binding)
        } else {
            0
        }
        val invoked = invokeOne(binding, allowManual, allowAutomatic)
        if (invoked > 0) reconciled += reconcileOne(binding)
        val dueAt = dao.earliestReconciliationDue(binding.accountId, now(), automaticBackupPolicy.isUnmetered())
        val ready = dao.findWriteSlot(binding.accountId) == null &&
            dao.countRunnableReady(binding.accountId, canRunAutomaticBackup(binding), allowManual = false) > 0
        val moreWork = dueAt != null || ready
        if (ready) scheduler.scheduleSoon()
        else if (dueAt != null) scheduler.scheduleAt(dueAt)
        WakeSummary(recovered, reconciled, stagedAutomatic, invoked, moreWork)
    }

    override suspend fun prepareTrash(items: Set<String>) = trash.prepareTrash(items)
    override suspend fun beginTrash(proposal: ReclaimProposalId) = trash.beginTrash(proposal)
    override suspend fun recordTrashResult(attempt: TrashAttemptId, result: SystemConsentCallback) =
        trash.recordTrashResult(attempt, result)

    override suspend fun accountWillChange(): AccountChangeResult {
        val active = dao.findActiveAccount()
        if (active == null) {
            if (dao.countPotentiallyExecutableTrashAttempts() > 0) {
                return AccountChangeResult.Blocked(
                    StoredMessage.A_SYSTEM_TRASH_REQUEST_MAY_STILL_EXECUTE_FINISH_THE_ANDROID_DIALOG_OR_RESTART_YOUR_DEVICE_AND_R.code,
                )
            }
            runSuspendCatching { scheduler.setAutomaticBackupEnabled(false) }
            return AccountChangeResult.Allowed
        }
        val result = accountGate(active.accountId).withLock {
            if (dao.countPotentiallyExecutableTrashAttempts() > 0) {
                return@withLock AccountChangeResult.Blocked(
                    StoredMessage.A_SYSTEM_TRASH_REQUEST_MAY_STILL_EXECUTE_FINISH_THE_ANDROID_DIALOG_OR_RESTART_YOUR_DEVICE_AND_R.code,
                )
            }
            database.withTransaction {
                dao.markReadyTransfersNeedAttention(
                    active.accountId,
                    now(),
                    StoredMessage.THE_ICLOUD_ACCOUNT_CHANGED_BEFORE_UPLOAD_SELECT_THIS_PHOTO_AGAIN_FOR_THE_NEW_ACCOUNT.code,
                )
                val enrollment = dao.findBackupEnrollment(active.accountId)
                dao.disableBackup(
                    active.accountId,
                    (enrollment?.operationFence ?: 0L) + 1L,
                    now(),
                )
                dao.deactivateActiveAccounts(now())
            }
            AccountChangeResult.Allowed
        }
        if (result is AccountChangeResult.Allowed) {
            deleteReleasableStages()
            runSuspendCatching { scheduler.setAutomaticBackupEnabled(false) }
        }
        return result
    }

    private suspend fun createTransfer(
        binding: AccountBinding,
        authorityType: String,
        authorityId: String,
        enrollment: BackupEnrollmentEntity?,
        mediaId: String?,
        contentUri: String,
        copy: suspend (OutputStream) -> me.lvca.alba.engine.source.ExactOriginalEvidence,
    ): UploadTicket {
        val staged = staging.stageExact(copy)
        val sourceId = UUID.randomUUID().toString()
        val transferId = UUID.randomUUID().toString()
        val createdAt = now()
        try {
            database.withTransaction {
                val active = refuseIfNull(dao.findActiveAccount(), StoredMessage.SIGN_IN_TO_ICLOUD_BEFORE_UPLOADING)
                refuseUnless(active.toBinding() == binding, StoredMessage.THE_ICLOUD_ACCOUNT_CHANGED_WHILE_STAGING_THIS_PHOTO)
                if (enrollment != null) {
                    val current = dao.findBackupEnrollment(binding.accountId)
                    refuseUnless(current?.enabled == true && current.revision == enrollment.revision &&
                        current.authorizedAuthEpoch == binding.authEpoch &&
                        current.permissionFingerprint == originals.permissionFingerprint(),
                        StoredMessage.AUTOMATIC_BACKUP_PERMISSION_OR_CONSENT_CHANGED,
                    )
                }
                refuseUnless(
                    dao.findExistingTransferForBytes(binding.accountId, staged.sha256Hex) == null,
                    StoredMessage.THESE_EXACT_BYTES_ARE_ALREADY_QUEUED_OR_VERIFIED_IN_ICLOUD,
                )
                refuseUnless(
                    dao.countActiveSuppressions(
                        binding.accountId,
                        staged.evidence.lineageKey,
                        staged.sha256Hex,
                    ) == 0,
                    StoredMessage.A_PREVIOUS_UPLOAD_OF_THESE_BYTES_IS_UNRESOLVED_AND_WILL_NOT_BE_REPEATED,
                )
                dao.insertStagedSource(
                    StagedSourceEntity(
                        stagedSourceId = sourceId,
                        accountId = binding.accountId,
                        authEpoch = binding.authEpoch,
                        operationFence = binding.operationFence,
                        authorityType = authorityType,
                        authorityId = authorityId,
                        enrollmentId = enrollment?.enrollmentId,
                        enrollmentRevision = enrollment?.revision,
                        mediaId = mediaId,
                        contentUri = contentUri,
                        displayName = staged.evidence.displayName,
                        mimeType = staged.evidence.mimeType,
                        lineageKey = staged.evidence.lineageKey,
                        sourceRevision = staged.evidence.sourceRevision,
                        originalAcquisition = staged.evidence.acquisitionMethod,
                        originalFormatRequested = staged.evidence.originalFormatRequested,
                        unredacted = staged.evidence.unredacted,
                        formatValidated = staged.evidence.formatValidated,
                        lastModifiedAtEpochMillis = staged.evidence.lastModifiedAtEpochMillis,
                        ownedFileToken = staged.ownedFileToken,
                        byteCount = staged.byteCount,
                        sha256Hex = staged.sha256Hex,
                        createdAtEpochMillis = createdAt,
                    ),
                )
                dao.insertTransfer(
                    TransferEntity(
                        transferId = transferId,
                        stagedSourceId = sourceId,
                        accountId = binding.accountId,
                        authEpoch = binding.authEpoch,
                        operationFence = binding.operationFence,
                        authorityType = authorityType,
                        authorityId = authorityId,
                        enrollmentId = enrollment?.enrollmentId,
                        enrollmentRevision = enrollment?.revision,
                        mediaId = mediaId,
                        displayName = staged.evidence.displayName,
                        state = TransferState.READY,
                        stateVersion = 0,
                        activeAttemptId = null,
                        leaseToken = null,
                        leaseExpiresAtEpochMillis = null,
                        reconciliationDueAtEpochMillis = null,
                        reconciliationDeadlineEpochMillis = null,
                        reconciliationChangeToken = null,
                        verifiedAtEpochMillis = null,
                        createdAtEpochMillis = createdAt,
                        updatedAtEpochMillis = createdAt,
                        message = StoredMessage.WAITING_TO_UPLOAD.code,
                    ),
                )
            }
        } catch (error: Throwable) {
            staging.deleteOwned(staged.ownedFileToken)
            throw error
        }
        if (authorityType == AUTHORITY_AUTOMATIC) runSuspendCatching { scheduler.scheduleSoon() }
        return UploadTicket(TransferId(transferId))
    }

    private suspend fun invokeOne(binding: AccountBinding, allowManual: Boolean, allowAutomatic: Boolean): Int {
        if (dao.findWriteSlot(binding.accountId) != null) return 0
        val candidate = dao.findNextReady(binding.accountId, now(), allowAutomatic && canRunAutomaticBackup(binding), allowManual) ?: return 0
        val leaseToken = UUID.randomUUID().toString()
        val leaseNow = now()
        if (dao.claimReady(
                candidate.transferId,
                candidate.stateVersion,
                leaseToken,
                leaseNow + READY_LEASE_MILLIS,
                leaseNow,
            ) != 1
        ) return 0
        val leased = requireNotNull(dao.findTransfer(candidate.transferId))
        val source = requireNotNull(dao.findStagedSource(leased.stagedSourceId))

        val uploadSource = try {
            staging.openVerified(source.ownedFileToken, source.byteCount, source.sha256Hex)
        } catch (error: Throwable) {
            // A cancelled check says nothing about the staged file; keep it and let the lease expire.
            if (error is CancellationException) throw error
            dao.markLeasedTransferNeedsAttention(
                leased.transferId,
                leaseToken,
                now(),
                StoredMessage.THE_STAGED_ORIGINAL_CHANGED_OR_IS_MISSING_SELECT_THE_PHOTO_AGAIN.code,
            )
            staging.deleteOwned(source.ownedFileToken)
            throw error
        }
        val preUploadToken = try {
            protocol.captureChangeToken(binding)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            dao.releaseReadyLease(leased.transferId, leaseToken, now(), StoredMessage.COULD_NOT_READ_THE_ICLOUD_CHANGE_CURSOR.code)
            return 0
        }
        val prepared = try {
            protocol.prepareOriginal(binding, source.displayName, uploadSource, source.lastModifiedAtEpochMillis)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            dao.releaseReadyLease(leased.transferId, leaseToken, now(), StoredMessage.COULD_NOT_PREPARE_THE_ICLOUD_UPLOAD.code)
            return 0
        }

        val attempt = accountGate(binding.accountId).withLock {
            if (!protocol.isWriteAuthorized() || (leased.authorityType == AUTHORITY_AUTOMATIC && !automaticBackupPolicy.isUnmetered())) {
                prepared.cancel()
                dao.releaseReadyLease(leased.transferId, leaseToken, now(), StoredMessage.WAITING_FOR_UPLOAD_AUTHORIZATION_AND_AN_ELIGIBLE_NETWORK.code)
                return@withLock null
            }
            val armed = database.withTransaction {
                val active = requireNotNull(dao.findActiveAccount())
                refuseUnless(active.toBinding() == binding, StoredMessage.THE_ACCOUNT_CHANGED_BEFORE_UPLOAD)
                val fresh = requireNotNull(dao.findTransfer(leased.transferId))
                if (fresh.state != TransferState.READY || fresh.leaseToken != leaseToken) return@withTransaction null
                val freshSource = requireNotNull(dao.findStagedSource(fresh.stagedSourceId))
                refuseUnless(freshSource.accountId == binding.accountId &&
                    freshSource.authEpoch == binding.authEpoch &&
                    freshSource.operationFence == binding.operationFence,
                    StoredMessage.THE_STAGED_SOURCE_NO_LONGER_HAS_WRITE_AUTHORITY,
                )
                if (fresh.authorityType == AUTHORITY_AUTOMATIC) {
                    val enrollment = requireNotNull(dao.findBackupEnrollment(binding.accountId))
                    refuseUnless(enrollment.enabled && enrollment.enrollmentId == fresh.enrollmentId &&
                        enrollment.revision == fresh.enrollmentRevision &&
                        enrollment.authorizedAuthEpoch == binding.authEpoch &&
                        enrollment.permissionFingerprint == originals.permissionFingerprint() &&
                        originals.hasAutomaticBackupAccess(),
                        StoredMessage.AUTOMATIC_BACKUP_PERMISSION_OR_CONSENT_CHANGED,
                    )
                }
                refuseUnless(
                    dao.countActiveSuppressions(
                        binding.accountId,
                        freshSource.lineageKey,
                        freshSource.sha256Hex,
                    ) == 0,
                    StoredMessage.THIS_UPLOAD_IS_SUPPRESSED_BECAUSE_AN_EARLIER_OUTCOME_IS_UNRESOLVED,
                )
                refuseUnless(dao.findWriteSlot(binding.accountId) == null, StoredMessage.ANOTHER_UPLOAD_IS_UNRESOLVED)

                val attemptId = UUID.randomUUID().toString()
                val authorizedAt = now()
                dao.insertAttempt(
                    UploadAttemptEntity(
                        attemptId = attemptId,
                        transferId = fresh.transferId,
                        stagedSourceId = fresh.stagedSourceId,
                        accountId = binding.accountId,
                        authEpoch = binding.authEpoch,
                        operationFence = binding.operationFence,
                        preUploadChangeToken = preUploadToken,
                        predecessorAttemptId = null,
                        authorizedAtEpochMillis = authorizedAt,
                    ),
                )
                dao.acquireWriteSlot(
                    AccountWriteSlotEntity(binding.accountId, attemptId, binding.authEpoch, authorizedAt),
                )
                fenceHolds(
                    dao.armSending(
                        transferId = fresh.transferId,
                        expectedVersion = fresh.stateVersion,
                        leaseToken = leaseToken,
                        accountId = binding.accountId,
                        authEpoch = binding.authEpoch,
                        operationFence = binding.operationFence,
                        attemptId = attemptId,
                        now = authorizedAt,
                    ) == 1,
                ) { "Upload authorization lost its compare-and-set" }
                UploadAttemptEntity(
                    attemptId, fresh.transferId, fresh.stagedSourceId, binding.accountId,
                    binding.authEpoch, binding.operationFence, preUploadToken, null, authorizedAt,
                )
            }
            if (armed == null) {
                prepared.cancel()
                return@withLock null
            }
            if (dao.findTransfer(armed.transferId)?.state != TransferState.SENDING) {
                prepared.cancel()
                return@withLock armed
            }

            if (leased.authorityType == AUTHORITY_AUTOMATIC && !automaticBackupPolicy.isUnmetered()) {
                prepared.cancel()
                recordUnknown(armed, "NETWORK_CHANGED_AFTER_COMMIT")
                return@withLock armed
            }
            if (leased.authorityType == AUTHORITY_AUTOMATIC &&
                (!originals.hasAutomaticBackupAccess() ||
                    dao.findBackupEnrollment(binding.accountId)?.permissionFingerprint != originals.permissionFingerprint())
            ) {
                recordUnknown(armed, "AUTHORITY_CHANGED_AFTER_COMMIT")
                return@withLock armed
            }

            try {
                val acceptance = prepared.startOnce(armed.attemptId)
                database.withTransaction {
                    dao.insertAcceptance(acceptance.toEntity(armed.attemptId, now()))
                    check(
                        dao.markReconciling(
                            armed.transferId,
                            armed.attemptId,
                            dueAt = now(),
                            deadline = now() + RECONCILIATION_DEADLINE_MILLIS,
                            now = now(),
                        ) == 1,
                    )
                }
            } catch (error: Throwable) {
                prepared.cancel()
                if (!protocol.canResume(binding, armed.attemptId)) {
                    recordUnknown(armed, error::class.java.simpleName.ifBlank { "NO_TRUSTED_RESPONSE" })
                } else if (leased.authorityType == AUTHORITY_AUTOMATIC) scheduler.scheduleSoon()
                if (error is CancellationException) throw error
            }
            armed
        }
        return if (attempt != null) 1 else 0
    }

    private suspend fun reconcileOne(binding: AccountBinding): Int {
        val transfer = dao.findNextReconciliation(binding.accountId, now(), automaticBackupPolicy.isUnmetered()) ?: return 0
        val attemptId = requireNotNull(transfer.activeAttemptId)
        val attempt = requireNotNull(dao.findAttempt(attemptId))
        val source = requireNotNull(dao.findStagedSource(attempt.stagedSourceId))
        val acceptance = dao.findAcceptance(attemptId)?.toModel()
        val cursor = transfer.reconciliationChangeToken ?: attempt.preUploadChangeToken
        // Processing is advisory. A failed status read must not prevent catalog/hash verification.
        val processing = try {
            acceptance?.let { protocol.uploadProcessingStatus(binding, it) }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            UploadProcessingStatus.Unknown
        }
        val page = try {
            if (processing is UploadProcessingStatus.Failed) {
                markUnresolved(transfer, attempt, source, TransferState.NOT_OBSERVED,
                    StoredMessage.UPLOAD_PROCESSING_FAILED.code)
                return 1
            }
            if (processing is UploadProcessingStatus.Processing &&
                now() < (transfer.reconciliationDeadlineEpochMillis ?: Long.MAX_VALUE)) {
                dao.markReadFailure(transfer.transferId, attemptId,
                    now() + RECONCILIATION_READ_RETRY_MILLIS, now(),
                    StoredMessage.UPLOAD_PROCESSING.code)
                return 1
            }
            protocol.findUploadCandidates(binding, cursor, acceptance, RECONCILIATION_PAGE_LIMIT)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            dao.markReadFailure(
                transfer.transferId,
                attemptId,
                now() + RECONCILIATION_READ_RETRY_MILLIS,
                now(),
                StoredMessage.COULD_NOT_CHECK_ICLOUD_YET_THE_UPLOAD_WILL_NOT_BE_REPEATED.code,
            )
            return 0
        }

        val exactMatches = mutableListOf<RemotePairCandidate>()
        for (pair in page.candidates.distinctBy { "${it.master.name}:${it.asset.name}" }) {
            if (!pair.relationIsCurrent || pair.masterDeleted || pair.assetDeleted) continue
            val sink = HashingOutputStream()
            val observation = try {
                protocol.streamFreshOriginal(binding, pair, sink)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                dao.markReadFailure(
                    transfer.transferId,
                    attemptId,
                    now() + RECONCILIATION_READ_RETRY_MILLIS,
                    now(),
                    StoredMessage.COULD_NOT_VERIFY_AN_ICLOUD_CANDIDATE_YET_THE_UPLOAD_WILL_NOT_BE_REPEATED.code,
                )
                return 0
            }
            if (sink.byteCount == source.byteCount &&
                observation.byteCount == source.byteCount &&
                sink.sha256Hex() == source.sha256Hex &&
                observation.resourceIdentity == pair.originalResourceIdentity
            ) {
                exactMatches += pair
            }
        }
        persistReconciliationMatches(binding, attemptId, exactMatches)
        val allMatches = dao.findReconciliationMatches(attemptId)

        when {
            page.moreComing && page.nextChangeToken != null -> dao.advanceReconciliationToken(
                transfer.transferId,
                attemptId,
                page.nextChangeToken,
                now(),
                now(),
            )
            page.moreComing -> dao.markReadFailure(
                transfer.transferId,
                attemptId,
                now() + RECONCILIATION_READ_RETRY_MILLIS,
                now(),
                StoredMessage.ICLOUD_RETURNED_AN_INCOMPLETE_CHANGE_PAGE_THE_UPLOAD_WILL_NOT_BE_REPEATED.code,
            )
            allMatches.size == 1 -> persistVerification(
                binding,
                transfer,
                attempt,
                source,
                allMatches.single().toCandidate(),
            )
            allMatches.size > 1 -> markUnresolved(
                transfer,
                attempt,
                source,
                TransferState.AMBIGUOUS,
                StoredMessage.MORE_THAN_ONE_EXACT_ICLOUD_COPY_MATCHED_ALBA_WILL_NOT_UPLOAD_AGAIN.code,
            )
            now() >= (transfer.reconciliationDeadlineEpochMillis ?: Long.MAX_VALUE) -> markUnresolved(
                transfer,
                attempt,
                source,
                TransferState.NOT_OBSERVED,
                StoredMessage.NO_EXACT_ICLOUD_COPY_WAS_OBSERVED_BEFORE_THE_CHECK_DEADLINE_ALBA_WILL_NOT_UPLOAD_AGAIN.code,
            )
            else -> dao.markReadFailure(
                transfer.transferId,
                attemptId,
                now() + RECONCILIATION_READ_RETRY_MILLIS,
                now(),
                StoredMessage.STILL_CHECKING_ICLOUD_THE_UPLOAD_WILL_NOT_BE_REPEATED.code,
            )
        }
        return 1
    }

    private suspend fun persistReconciliationMatches(
        binding: AccountBinding,
        attemptId: String,
        matches: List<RemotePairCandidate>,
    ) {
        if (matches.isEmpty()) return
        val observedAt = now()
        database.withTransaction {
            matches.forEach { pair ->
                val entity = pair.toEntity(binding, observedAt)
                dao.insertRemotePair(entity)
                dao.insertReconciliationMatch(
                    ReconciliationMatchEntity(attemptId, entity.remotePairId, observedAt),
                )
            }
        }
    }

    private suspend fun persistVerification(
        binding: AccountBinding,
        transfer: TransferEntity,
        attempt: UploadAttemptEntity,
        source: StagedSourceEntity,
        pair: RemotePairCandidate,
    ) {
        val verifiedAt = now()
        val pairId = stableId(binding.accountId, pair.master.name, pair.asset.name)
        val linkId = UUID.randomUUID().toString()
        val proofId = UUID.randomUUID().toString()
        database.withTransaction {
            val fresh = requireNotNull(dao.findTransfer(transfer.transferId))
            require(fresh.state in RECONCILABLE_STATES && fresh.activeAttemptId == attempt.attemptId)
            dao.insertRemotePair(pair.toEntity(binding, verifiedAt))
            dao.insertAssetLink(
                AssetLinkEntity(
                    linkId, binding.accountId, transfer.mediaId, source.stagedSourceId,
                    pairId, attempt.attemptId, verifiedAt, "CURRENT",
                ),
            )
            dao.insertVerificationProof(
                VerificationProofEntity(
                    proofId = proofId,
                    accountId = binding.accountId,
                    verificationAuthEpoch = binding.authEpoch,
                    invocationAuthEpoch = attempt.authEpoch,
                    linkId = linkId,
                    mediaId = transfer.mediaId,
                    stagedSourceId = source.stagedSourceId,
                    sourceRevision = source.sourceRevision,
                    remotePairId = pairId,
                    masterChangeTag = pair.master.changeTag,
                    assetChangeTag = pair.asset.changeTag,
                    originalResourceIdentity = pair.originalResourceIdentity,
                    byteCount = source.byteCount,
                    sha256Hex = source.sha256Hex,
                    verifiedAtEpochMillis = verifiedAt,
                    invalidatedAtEpochMillis = null,
                    invalidationReason = null,
                ),
            )
            check(dao.markVerified(transfer.transferId, attempt.attemptId, verifiedAt) == 1)
            check(dao.releaseWriteSlot(binding.accountId, attempt.attemptId) == 1)
        }
        releaseStage(source)
    }

    private suspend fun markUnresolved(
        transfer: TransferEntity,
        attempt: UploadAttemptEntity,
        source: StagedSourceEntity,
        state: TransferState,
        message: String,
    ) {
        database.withTransaction {
            check(dao.markUnresolvedTerminal(transfer.transferId, attempt.attemptId, state, now(), message) == 1)
            dao.insertSuppression(
                UploadSuppressionEntity(
                    suppressionId = UUID.randomUUID().toString(),
                    accountId = transfer.accountId,
                    attemptId = attempt.attemptId,
                    lineageKey = source.lineageKey,
                    sha256Hex = source.sha256Hex,
                    reason = state.name,
                    active = true,
                    createdAtEpochMillis = now(),
                ),
            )
        }
    }

    private suspend fun recordUnknown(attempt: UploadAttemptEntity, reason: String) {
        val observedAt = now()
        database.withTransaction {
            dao.insertUncertainty(UploadUncertaintyEntity(attempt.attemptId, reason, observedAt))
            val changed = dao.markOutcomeUnknown(
                attempt.transferId,
                attempt.attemptId,
                observedAt,
                observedAt + RECONCILIATION_DEADLINE_MILLIS,
            )
            if (changed == 0) {
                // Notification cancellation may have already stopped this attempt.
                val current = dao.findTransfer(attempt.transferId)
                check(current?.activeAttemptId == attempt.attemptId && current.state != TransferState.READY) {
                    "The uncertain upload no longer owns its transfer"
                }
            }
        }
    }

    private suspend fun recoverInterruptedInvocations(binding: AccountBinding?, allowManual: Boolean, allowAutomatic: Boolean): Int {
        if (binding == null || !protocol.isWriteAuthorized()) return 0
        val interrupted = dao.findInterruptedSending()
        interrupted.forEach { transfer ->
            val attemptId = requireNotNull(transfer.activeAttemptId)
            val observedAt = now()
            if (transfer.authorityType == AUTHORITY_AUTOMATIC && !allowAutomatic) return@forEach
            if (transfer.authorityType == AUTHORITY_AUTOMATIC && !automaticBackupPolicy.isUnmetered()) {
                return@forEach
            }
            if (transfer.accountId == binding.accountId && transfer.authEpoch == binding.authEpoch &&
                transfer.operationFence == binding.operationFence && protocol.canResume(binding, attemptId)) {
                if (transfer.authorityType == AUTHORITY_MANUAL && !allowManual) return@forEach
                val attempt = requireNotNull(dao.findAttempt(attemptId))
                try {
                    accountGate(binding.accountId).withLock {
                        refuseUnless(dao.findActiveAccount()?.toBinding() == binding, StoredMessage.THE_ACCOUNT_CHANGED_BEFORE_UPLOAD)
                        if (transfer.authorityType == AUTHORITY_AUTOMATIC) {
                            val enrollment = dao.findEnabledBackupEnrollment(binding.accountId)
                            refuseUnless(enrollment != null &&
                                enrollment.enrollmentId == transfer.enrollmentId && enrollment.revision == transfer.enrollmentRevision &&
                                enrollment.authorizedAuthEpoch == binding.authEpoch && originals.hasAutomaticBackupAccess() &&
                                enrollment.permissionFingerprint == originals.permissionFingerprint(),
                                StoredMessage.AUTOMATIC_BACKUP_PERMISSION_OR_CONSENT_CHANGED,
                            )
                        }
                        if (transfer.authorityType == AUTHORITY_AUTOMATIC && !automaticBackupPolicy.isUnmetered()) return@withLock
                        val source = refuseIfNull(
                            dao.findStagedSource(transfer.stagedSourceId),
                            StoredMessage.THE_STAGED_ORIGINAL_CHANGED_OR_IS_MISSING_SELECT_THE_PHOTO_AGAIN,
                        )
                        val upload = try {
                            staging.openVerified(source.ownedFileToken, source.byteCount, source.sha256Hex)
                        } catch (_: StagedOriginalUnavailableException) {
                            throw PhotoCompanionException.Refused(StoredMessage.THE_STAGED_ORIGINAL_CHANGED_OR_IS_MISSING_SELECT_THE_PHOTO_AGAIN)
                        }
                        val prepared = protocol.prepareOriginal(binding, source.displayName, upload, source.lastModifiedAtEpochMillis)
                        if (!protocol.isWriteAuthorized() || (transfer.authorityType == AUTHORITY_AUTOMATIC && !automaticBackupPolicy.isUnmetered())) {
                            prepared.cancel()
                            return@withLock
                        }
                        if (dao.findTransfer(transfer.transferId)?.state != TransferState.SENDING) {
                            prepared.cancel()
                            return@withLock
                        }
                        val acceptance = prepared.startOnce(attemptId)
                        database.withTransaction {
                            dao.insertAcceptance(acceptance.toEntity(attemptId, now()))
                            fenceHolds(dao.markReconciling(transfer.transferId, attemptId, now(), now() + RECONCILIATION_DEADLINE_MILLIS, now()) == 1)
                        }
                    }
                    return@forEach
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (!protocol.isWriteAuthorized()) return@forEach
                    // Alba's own refusals are final. Any other failure with durable safe state can continue at the next wake.
                    if (!error.isFinalRefusal() && protocol.canResume(binding, attemptId)) {
                        if (transfer.authorityType == AUTHORITY_AUTOMATIC) scheduler.scheduleSoon()
                        return@forEach
                    }
                    recordUnknown(attempt, "RESUME_INTERRUPTED")
                    return@forEach
                }
            }
            database.withTransaction {
                dao.insertUncertainty(
                    UploadUncertaintyEntity(attemptId, "PROCESS_INTERRUPTED_AFTER_AUTHORIZATION", observedAt),
                )
                dao.markOutcomeUnknown(
                    transfer.transferId,
                    attemptId,
                    observedAt,
                    observedAt + RECONCILIATION_DEADLINE_MILLIS,
                )
            }
        }
        return interrupted.size
    }

    private suspend fun canRunAutomaticBackup(binding: AccountBinding): Boolean {
        if (!automaticBackupPolicy.isUnmetered() || !originals.hasAutomaticBackupAccess()) return false
        val enrollment = dao.findEnabledBackupEnrollment(binding.accountId) ?: return false
        return enrollment.authorizedAuthEpoch == binding.authEpoch &&
            enrollment.permissionFingerprint == originals.permissionFingerprint()
    }

    private suspend fun discoverAndStageAutomatic(binding: AccountBinding): Int {
        if (!automaticBackupPolicy.isUnmetered()) return 0
        val enrollment = dao.findEnabledBackupEnrollment(binding.accountId) ?: return 0
        val permissionFingerprint = originals.permissionFingerprint()
        if (enrollment.accountId != binding.accountId ||
            enrollment.authorizedAuthEpoch != binding.authEpoch ||
            enrollment.permissionFingerprint != permissionFingerprint ||
            !originals.hasAutomaticBackupAccess()
        ) return 0

        val checkpointId = "${binding.accountId}:${enrollment.enrollmentId}:${enrollment.revision}:external"
        val checkpoint = dao.findMediaStoreCheckpoint(checkpointId)
            ?.takeIf { it.permissionFingerprint == permissionFingerprint }
            ?.let {
            LocalDiscoveryCheckpoint(
                it.volumeName, it.volumeVersion, it.generationModified, it.mediaStoreId,
            )
        }
        val page = originals.discoverEligibleJpegs(
            checkpoint, AUTO_DISCOVERY_PAGE_SIZE, BackupScope.valueOf(enrollment.scope), enrollment.consentedAtEpochMillis,
            enrollment.discoveryBaselineVersion?.let { version ->
                enrollment.discoveryBaselineGeneration?.let { generation ->
                    LocalDiscoveryCheckpoint("external", version, generation, Long.MAX_VALUE)
                }
            },
        )
        val scannedAt = now()
        database.withTransaction {
            val current = requireNotNull(dao.findBackupEnrollment(binding.accountId))
            require(current == enrollment) { "Backup enrollment changed during discovery" }
            dao.upsertLocalAssets(page.items.map { item ->
                LocalAssetEntity(
                    localAssetId = item.id,
                    volumeName = item.volumeName,
                    volumeVersion = item.volumeVersion,
                    mediaStoreId = item.mediaStoreId,
                    generationModified = item.generationModified,
                    contentUri = item.contentUri,
                    lineageKey = "${item.volumeName}:${item.mediaStoreId}",
                    displayName = item.fileName,
                    mimeType = item.mimeType,
                    byteCount = item.byteCount,
                    capturedAtEpochMillis = item.capturedAtEpochMillis,
                    permissionFingerprint = permissionFingerprint,
                    lastSeenAtEpochMillis = scannedAt,
                )
            })

        }
        suspend fun saveCheckpoint() {
            dao.upsertMediaStoreCheckpoint(
                MediaStoreCheckpointEntity(
                    checkpointId, page.checkpoint.volumeName, page.checkpoint.volumeVersion,
                    permissionFingerprint, page.checkpoint.generationModified, page.checkpoint.mediaStoreId, scannedAt,
                ),
            )
        }
        if (page.moreComing) runSuspendCatching { scheduler.scheduleSoon() }
        // Cached rows can belong to an older enrollment; use only the scoped page.
        val item = page.items.firstOrNull()
        if (item == null) {
            saveCheckpoint()
            return 0
        }
        val local = requireNotNull(dao.findLocalAsset(item.id))
        if (dao.findTransferForMedia(binding.accountId, local.localAssetId) != null) {
            saveCheckpoint()
            return 0
        }
        createTransfer(
            binding = binding,
            authorityType = AUTHORITY_AUTOMATIC,
            authorityId = "enrollment:${enrollment.enrollmentId}:${enrollment.revision}",
            enrollment = enrollment,
            mediaId = local.localAssetId,
            contentUri = local.contentUri,
        ) { output ->
            originals.copyMediaStoreItem(local.contentUri, localRevision(local), output)
        }
        // Failed staging must not advance past the photo.
        saveCheckpoint()
        return 1
    }

    private suspend fun synchronizeAccount(): AccountBinding? {
        val destination = protocol.currentDestination() ?: return null
        var replacedActiveAccount = false
        val binding = database.withTransaction {
            val active = dao.findActiveAccount()
            if (active != null && active.accountId == destination.accountId &&
                active.authSessionId == destination.authSessionId
            ) {
                if (active.destinationLabel != destination.label) {
                    dao.upsertAccount(active.copy(destinationLabel = destination.label, updatedAtEpochMillis = now()))
                }
                return@withTransaction active.toBinding()
            }

            if (active != null) {
                refuseUnless(
                    dao.countPotentiallyExecutableTrashAttempts() == 0,
                    StoredMessage.A_SYSTEM_TRASH_REQUEST_MAY_STILL_EXECUTE_FOR_THE_PREVIOUS_ICLOUD_ACCOUNT,
                )
                dao.markReadyTransfersNeedAttention(
                    active.accountId,
                    now(),
                    StoredMessage.THE_AUTHENTICATED_ICLOUD_SESSION_CHANGED_BEFORE_UPLOAD_SELECT_THIS_PHOTO_AGAIN.code,
                )
                val enrollment = dao.findBackupEnrollment(active.accountId)
                dao.disableBackup(
                    active.accountId,
                    (enrollment?.operationFence ?: 0L) + 1L,
                    now(),
                )
                dao.deactivateActiveAccounts(now())
                replacedActiveAccount = true
            }
            val previous = dao.findAccount(destination.accountId)
            val epoch = when {
                previous == null -> 1L
                previous.authSessionId == destination.authSessionId -> previous.authEpoch
                else -> previous.authEpoch + 1L
            }
            val account = AccountEntity(
                accountId = destination.accountId,
                authSessionId = destination.authSessionId,
                destinationLabel = destination.label,
                authEpoch = epoch,
                operationFence = (previous?.operationFence ?: 0L) + 1L,
                active = true,
                updatedAtEpochMillis = now(),
            )
            dao.upsertAccount(account)
            account.toBinding()
        }
        if (replacedActiveAccount) runSuspendCatching { scheduler.setAutomaticBackupEnabled(false) }
        return binding
    }

    private suspend fun requireActiveBinding(): AccountBinding = synchronizeAccount()
        ?: throw PhotoCompanionException.Refused(StoredMessage.SIGN_IN_TO_ICLOUD_BEFORE_UPLOADING)

    private fun accountGate(accountId: String): Mutex = accountGates.getOrPut(accountId, ::Mutex)

    private suspend fun sweepOrphanStages() {
        staging.sweepOrphans(dao.findOwnedStageTokens().toSet())
    }

    private suspend fun deleteReleasableStages() {
        val releasedSourceIds = dao.findReleasableStages().mapNotNull { source ->
            source.stagedSourceId.takeIf { staging.deleteOwned(source.ownedFileToken) }
        }
        if (releasedSourceIds.isNotEmpty()) {
            dao.markStagesReleased(releasedSourceIds, now())
        }
    }

    private suspend fun releaseStage(source: StagedSourceEntity) {
        if (staging.deleteOwned(source.ownedFileToken)) {
            dao.markStagesReleased(listOf(source.stagedSourceId), now())
        }
    }

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
    )

    private fun UploadAcceptance.toEntity(attemptId: String, receivedAt: Long) = UploadAcceptanceEntity(
        attemptId, requestUuid, masterRecordName, assetRecordName, duplicateHint, receivedAt, uploadJobId,
    )

    private fun UploadAcceptanceEntity.toModel() = UploadAcceptance(
        requestUuid, masterRecordName, assetRecordName, duplicateHint, uploadJobId,
    )

    private fun RemotePairCandidate.toEntity(
        binding: AccountBinding,
        observedAt: Long,
    ) = RemoteAssetPairEntity(
        remotePairId = stableId(binding.accountId, master.name, asset.name),
        accountId = binding.accountId,
        observedAuthEpoch = binding.authEpoch,
        databaseScope = "com.apple.photos.cloud/private",
        zoneName = "PrimarySync",
        masterRecordName = master.name,
        masterChangeTag = master.changeTag,
        masterDeleted = masterDeleted,
        assetRecordName = asset.name,
        assetChangeTag = asset.changeTag,
        assetDeleted = assetDeleted,
        relationIsCurrent = relationIsCurrent,
        originalResourceIdentity = originalResourceIdentity,
        hydratedAtEpochMillis = observedAt,
    )

    private fun localRevision(local: LocalAssetEntity): String =
        "${local.volumeVersion}:${local.volumeName}:${local.mediaStoreId}:${local.generationModified}:${local.byteCount}"

    private fun stableId(vararg values: String): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val AUTHORITY_MANUAL = "MANUAL"
        const val AUTHORITY_AUTOMATIC = "AUTOMATIC"
        const val READY_LEASE_MILLIS = 2 * 60 * 1_000L
        const val RECONCILIATION_DEADLINE_MILLIS = 15 * 60 * 1_000L
        const val RECONCILIATION_READ_RETRY_MILLIS = 15 * 1_000L
        const val RECONCILIATION_PAGE_LIMIT = 200
        const val AUTO_DISCOVERY_PAGE_SIZE = 1
        val RECONCILABLE_STATES = setOf(TransferState.OUTCOME_UNKNOWN, TransferState.RECONCILING, TransferState.READ_FAILURE)
    }
}
