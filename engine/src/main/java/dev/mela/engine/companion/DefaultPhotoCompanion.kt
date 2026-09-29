package dev.mela.engine.companion

import android.Manifest
import android.os.Build
import androidx.room.withTransaction
import dev.mela.engine.cache.UploadStagingStore
import dev.mela.engine.database.AccountEntity
import dev.mela.engine.database.AccountWriteSlotEntity
import dev.mela.engine.database.AssetLinkEntity
import dev.mela.engine.database.BackupEnrollmentDraftEntity
import dev.mela.engine.database.BackupEnrollmentEntity
import dev.mela.engine.database.LocalAssetEntity
import dev.mela.engine.database.MediaStoreCheckpointEntity
import dev.mela.engine.database.MelaDatabase
import dev.mela.engine.database.ReclaimProposalEntity
import dev.mela.engine.database.ReclaimProposalItemEntity
import dev.mela.engine.database.ReconciliationMatchEntity
import dev.mela.engine.database.RemoteAssetPairEntity
import dev.mela.engine.database.StagedSourceEntity
import dev.mela.engine.database.TransferEntity
import dev.mela.engine.database.TransferProjection
import dev.mela.engine.database.TrashAttemptEntity
import dev.mela.engine.database.TrashObligationEntity
import dev.mela.engine.database.UploadAcceptanceEntity
import dev.mela.engine.database.UploadAttemptEntity
import dev.mela.engine.database.UploadSuppressionEntity
import dev.mela.engine.database.UploadUncertaintyEntity
import dev.mela.engine.database.VerificationProofEntity
import dev.mela.engine.model.AutomaticBackupPolicy
import dev.mela.engine.model.AccountBinding
import dev.mela.engine.model.AccountChangeResult
import dev.mela.engine.model.BackupChange
import dev.mela.engine.model.BackupDraft
import dev.mela.engine.model.BackupDraftId
import dev.mela.engine.model.BackupScope
import dev.mela.engine.model.BackupSetupEffect
import dev.mela.engine.model.BackupView
import dev.mela.engine.model.CandidatePage
import dev.mela.engine.model.ICloudPhotoProtocol
import dev.mela.engine.model.MaintenanceWakeup
import dev.mela.engine.model.PhotoCompanion
import dev.mela.engine.model.ReclaimProposal
import dev.mela.engine.model.ReclaimProposalId
import dev.mela.engine.model.RemotePairCandidate
import dev.mela.engine.model.RemoteRecordRef
import dev.mela.engine.model.SyncTicket
import dev.mela.engine.model.SystemConsentCallback
import dev.mela.engine.model.TransferId
import dev.mela.engine.model.TransferView
import dev.mela.engine.model.TransferViewState
import dev.mela.engine.model.TrashAttemptId
import dev.mela.engine.model.TrashHandoff
import dev.mela.engine.model.UploadProcessingStatus
import dev.mela.engine.model.UploadAcceptance
import dev.mela.engine.model.UploadTicket
import dev.mela.engine.model.UserSelectedPhoto
import dev.mela.engine.model.WakeReason
import dev.mela.engine.model.WakeSummary
import dev.mela.engine.model.WakeupScheduler
import dev.mela.engine.source.ExactOriginalSource
import dev.mela.engine.source.LocalDiscoveryCheckpoint
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
    private val database: MelaDatabase,
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

    override fun observeTransfers(): Flow<List<TransferView>> = dao.observeTransfers().map { rows ->
        rows.map(::toTransferView)
    }

    override fun observeBackup(): Flow<BackupView> = dao.observeBackup().map { row ->
        if (row == null) {
            BackupView(enabled = false)
        } else {
            BackupView(
                enabled = row.enabled,
                destinationLabel = row.destinationLabel,
                scope = BackupScope.valueOf(row.scope),
                message = when {
                    !row.enabled -> "Automatic backup is off."
                    !originals.hasAutomaticBackupAccess() -> "Backup paused: allow full photo and media location access."
                    !automaticBackupPolicy.isUnmetered() -> "Backup paused: waiting for an unmetered network."
                    else -> "New camera JPEGs will back up automatically."
                },
            )
        }
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
        val item = requireNotNull(database.catalogDao().findById(mediaId)) { "Unknown device photo" }
        require(item.origin == "DEVICE") { "Only photos on this phone can be uploaded" }
        val contentUri = requireNotNull(item.localUri) { "This photo is no longer available on the phone" }
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

    override suspend fun beginBackupEnrollment(scope: BackupScope): BackupDraft {
        val binding = requireActiveBinding()
        val account = requireNotNull(dao.findAccount(binding.accountId))
        val createdAt = now()
        val draft = BackupEnrollmentDraftEntity(
            draftId = UUID.randomUUID().toString(),
            accountId = binding.accountId,
            authEpoch = binding.authEpoch,
            operationFence = binding.operationFence,
            destinationLabel = account.destinationLabel,
            scope = scope.name,
            disclosureRevision = BACKUP_DISCLOSURE_REVISION,
            phase = "SHOWN",
            shownAtEpochMillis = createdAt,
            acceptedAtEpochMillis = null,
            expiresAtEpochMillis = createdAt + BACKUP_DRAFT_LIFETIME_MILLIS,
        )
        dao.insertBackupDraft(draft)
        return BackupDraft(
            id = BackupDraftId(draft.draftId),
            destinationLabel = draft.destinationLabel,
            disclosure = "Alba will automatically read newly added JPEG photos in DCIM/Camera on this phone and upload exact " +
                "original bytes directly to ${draft.destinationLabel} on an unmetered network. Uploads pause when access, consent, " +
                "or the destination account changes.",
        )
    }

    override suspend fun acceptBackupDisclosure(draft: BackupDraftId): BackupSetupEffect {
        val row = dao.findBackupDraft(draft.value)
            ?: return BackupSetupEffect.Refused("This backup setup has expired. Start again.")
        val binding = synchronizeAccount()
            ?: return BackupSetupEffect.Refused("Sign in to iCloud before enabling automatic backup.")
        if (row.accountId != binding.accountId || row.authEpoch != binding.authEpoch ||
            row.operationFence != binding.operationFence || row.expiresAtEpochMillis <= now()
        ) {
            return BackupSetupEffect.Refused("The destination account changed. Start backup setup again.")
        }
        if (dao.acceptBackupDraft(draft.value, now()) != 1) {
            return BackupSetupEffect.Refused("This backup setup is no longer active.")
        }
        return BackupSetupEffect.RequestMediaPermissions(automaticBackupPermissions())
    }

    override suspend fun finishBackupEnrollment(draft: BackupDraftId): BackupView {
        val row = requireNotNull(dao.findBackupDraft(draft.value)) { "Backup setup expired" }
        require(row.phase == "CONSENTED") { "Accept the backup disclosure first" }
        val binding = requireActiveBinding()
        require(row.accountId == binding.accountId && row.authEpoch == binding.authEpoch) {
            "The destination account changed. Start backup setup again."
        }
        require(originals.hasAutomaticBackupAccess()) {
            "Automatic backup needs full photo access and precise media location access."
        }

        val discoveryBaseline = originals.captureEnrollmentBaseline()
        val view = accountGate(binding.accountId).withLock {
            database.withTransaction {
                val active = requireNotNull(dao.findActiveAccount())
                require(active.accountId == binding.accountId && active.authEpoch == binding.authEpoch &&
                    active.operationFence == binding.operationFence
                ) { "The account changed while backup was being enabled" }
                val current = dao.findBackupEnrollment(active.accountId)
                check(dao.completeBackupDraft(draft.value) == 1) {
                    "This backup setup was already completed"
                }
                val enrollment = BackupEnrollmentEntity(
                    enrollmentId = current?.enrollmentId ?: UUID.randomUUID().toString(),
                    accountId = active.accountId,
                    authorizedAuthEpoch = active.authEpoch,
                    revision = (current?.revision ?: 0L) + 1L,
                    operationFence = (current?.operationFence ?: 0L) + 1L,
                    scope = row.scope,
                    discoveryBaselineVersion = discoveryBaseline?.volumeVersion,
                    discoveryBaselineGeneration = discoveryBaseline?.generationModified,
                    disclosureRevision = row.disclosureRevision,
                    consentedAtEpochMillis = requireNotNull(row.acceptedAtEpochMillis),
                    permissionFingerprint = originals.permissionFingerprint(),
                    enabled = true,
                    updatedAtEpochMillis = now(),
                )
                dao.upsertBackupEnrollment(enrollment)
                BackupView(
                    enabled = true,
                    destinationLabel = active.destinationLabel,
                    scope = BackupScope.valueOf(enrollment.scope),
                    message = "Automatic JPEG backup is on.",
                )
            }
        }
        scheduler.setAutomaticBackupEnabled(true)
        scheduler.scheduleSoon()
        return view
    }

    override suspend fun changeBackup(change: BackupChange): BackupView = when (change) {
        BackupChange.Disable -> disableAutomaticBackup()
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
                if (transfer.state == "SENDING") throw java.io.IOException("Upload interrupted; waiting for the transfer runner to retry")
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
        require(row.state == "AMBIGUOUS" || row.state == "NOT_OBSERVED") {
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
        runCatching { scheduler.scheduleSoon() }
    }

    override suspend fun wake(reason: WakeReason): WakeSummary = maintenanceGate.withLock {
        val allowManual = reason == WakeReason.USER_REQUESTED || reason == WakeReason.USER_TRANSFER
        val allowAutomatic = reason != WakeReason.USER_TRANSFER
        val binding = synchronizeAccount()
        val recovered = recoverInterruptedInvocations(binding, allowManual, allowAutomatic)
        dao.recoverTrashHandoffs()
        inspectApprovedTrashAttempts()
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

    override suspend fun prepareTrash(items: Set<String>): ReclaimProposal {
        require(items.isNotEmpty()) { "Select at least one verified photo" }
        val binding = requireActiveBinding()
        val proposalId = UUID.randomUUID().toString()
        val checked = mutableListOf<ReclaimProposalItemEntity>()
        val checkedAt = now()

        for (mediaId in items) {
            val proof = dao.findCurrentProof(mediaId, binding.accountId)
                ?: return blockedProposal(proposalId, "This photo does not have a current exact iCloud proof.")
            val source = dao.findStagedSource(proof.stagedSourceId)
                ?: return blockedProposal(proposalId, "The local proof source is unavailable.")
            val pairEntity = dao.findRemotePair(proof.remotePairId)
                ?: return blockedProposal(proposalId, "The iCloud record proof is unavailable.")
            require(source.accountId == binding.accountId && proof.accountId == binding.accountId)

            val local = originals.hashMediaStoreItem(source.contentUri, source.sourceRevision)
            val remoteHash = HashingOutputStream()
            val remote = protocol.streamFreshOriginal(binding, pairEntity.toCandidate(), remoteHash)
            val exact = local.sha256Hex == proof.sha256Hex &&
                local.byteCount == proof.byteCount &&
                remoteHash.sha256Hex() == proof.sha256Hex &&
                remoteHash.byteCount == proof.byteCount &&
                remote.byteCount == proof.byteCount &&
                remote.resourceIdentity == pairEntity.originalResourceIdentity
            if (!exact) {
                return blockedProposal(proposalId, "The current local and iCloud originals no longer match.")
            }
            checked += ReclaimProposalItemEntity(
                proposalId = proposalId,
                mediaId = mediaId,
                contentUri = source.contentUri,
                proofId = proof.proofId,
                localSha256Hex = local.sha256Hex,
                remoteSha256Hex = remoteHash.sha256Hex(),
                verifiedAtEpochMillis = checkedAt,
            )
        }

        val expiresAt = checkedAt + RECLAIM_PROPOSAL_LIFETIME_MILLIS
        database.withTransaction {
            val active = requireNotNull(dao.findActiveAccount())
            require(active.toBinding() == binding) { "The account changed during the final iCloud check" }
            dao.insertReclaimProposal(
                ReclaimProposalEntity(
                    proposalId = proposalId,
                    accountId = binding.accountId,
                    authEpoch = binding.authEpoch,
                    operationFence = binding.operationFence,
                    state = "READY",
                    createdAtEpochMillis = checkedAt,
                    expiresAtEpochMillis = expiresAt,
                    blockedReason = null,
                ),
            )
            dao.insertReclaimProposalItems(checked)
        }
        return ReclaimProposal(ReclaimProposalId(proposalId), items, expiresAt)
    }

    override suspend fun beginTrash(proposal: ReclaimProposalId): TrashHandoff {
        val row = requireNotNull(dao.findReclaimProposal(proposal.value)) { "Trash proposal expired" }
        require(row.state == "READY" && row.expiresAtEpochMillis > now()) { "Trash proposal expired" }
        val items = dao.findReclaimProposalItems(proposal.value)
        require(items.isNotEmpty()) { "Trash proposal has no photos" }
        val binding = requireActiveBinding()
        require(row.accountId == binding.accountId && row.authEpoch == binding.authEpoch &&
            row.operationFence == binding.operationFence
        ) { "The iCloud account changed before Android consent" }
        val intentSender = originals.createTrashRequest(items.map(ReclaimProposalItemEntity::contentUri))

        return accountGate(binding.accountId).withLock {
            val attemptId = UUID.randomUUID().toString()
            val handedOffAt = now()
            database.withTransaction {
                val active = requireNotNull(dao.findActiveAccount())
                require(active.toBinding() == binding) { "The account changed before Android consent" }
                check(dao.markReclaimProposalHandedOff(proposal.value, handedOffAt) == 1) {
                    "This trash request was already used"
                }
                dao.insertTrashAttempt(
                    TrashAttemptEntity(
                        trashAttemptId = attemptId,
                        proposalId = proposal.value,
                        accountId = binding.accountId,
                        authEpoch = binding.authEpoch,
                        operationFence = binding.operationFence,
                        consentDisposition = "HANDED_OFF_EXECUTION_POSSIBLE",
                        handedOffAtEpochMillis = handedOffAt,
                        callbackAtEpochMillis = null,
                        callbackResult = null,
                        bootSessionId = originals.bootSessionId(),
                    ),
                )
                dao.insertTrashObligations(items.map { item ->
                    TrashObligationEntity(
                        trashAttemptId = attemptId,
                        mediaId = item.mediaId,
                        contentUri = item.contentUri,
                        proofId = item.proofId,
                        uriState = "AWAITING_SYSTEM_RESULT",
                        trashExpiresAtEpochMillis = null,
                        resolvedAtEpochMillis = null,
                    )
                })
            }
            TrashHandoff(TrashAttemptId(attemptId), intentSender)
        }
    }

    override suspend fun recordTrashResult(attempt: TrashAttemptId, result: SystemConsentCallback) {
        val disposition = if (result == SystemConsentCallback.APPROVED) {
            "CALLBACK_OBSERVED_APPROVED"
        } else {
            "TERMINAL_DENIED"
        }
        check(dao.recordTrashCallback(attempt.value, disposition, result.name, now()) == 1) {
            "This Android trash result is no longer active"
        }
        if (result == SystemConsentCallback.APPROVED) {
            inspectTrashAttempt(attempt.value)
            runCatching { scheduler.scheduleSoon() }
        } else {
            resolveDeniedTrashAttempt(attempt.value)
        }
    }

    override suspend fun accountWillChange(): AccountChangeResult {
        val active = dao.findActiveAccount()
        if (active == null) {
            if (dao.countPotentiallyExecutableTrashAttempts() > 0) {
                return AccountChangeResult.Blocked(
                    "A system trash request may still execute. Finish the Android dialog, or restart your device and reopen Mela before changing accounts.",
                )
            }
            runCatching { scheduler.setAutomaticBackupEnabled(false) }
            return AccountChangeResult.Allowed
        }
        val result = accountGate(active.accountId).withLock {
            if (dao.countPotentiallyExecutableTrashAttempts() > 0) {
                return@withLock AccountChangeResult.Blocked(
                    "A system trash request may still execute. Finish the Android dialog, or restart your device and reopen Mela before changing accounts.",
                )
            }
            database.withTransaction {
                dao.markReadyTransfersNeedAttention(
                    active.accountId,
                    now(),
                    "The iCloud account changed before upload. Select this photo again for the new account.",
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
            runCatching { scheduler.setAutomaticBackupEnabled(false) }
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
        copy: suspend (OutputStream) -> dev.mela.engine.source.ExactOriginalEvidence,
    ): UploadTicket {
        val staged = staging.stageExact(copy)
        val sourceId = UUID.randomUUID().toString()
        val transferId = UUID.randomUUID().toString()
        val createdAt = now()
        try {
            database.withTransaction {
                val active = requireNotNull(dao.findActiveAccount()) { "Sign in to iCloud before uploading" }
                require(active.toBinding() == binding) { "The iCloud account changed while staging this photo" }
                if (enrollment != null) {
                    val current = dao.findBackupEnrollment(binding.accountId)
                    require(current?.enabled == true && current.revision == enrollment.revision &&
                        current.authorizedAuthEpoch == binding.authEpoch &&
                        current.permissionFingerprint == originals.permissionFingerprint()
                    ) { "Automatic backup permission or consent changed" }
                }
                check(
                    dao.findExistingTransferForBytes(binding.accountId, staged.sha256Hex) == null,
                ) { "These exact bytes are already queued or verified in iCloud." }
                check(
                    dao.countActiveSuppressions(
                        binding.accountId,
                        staged.evidence.lineageKey,
                        staged.sha256Hex,
                    ) == 0,
                ) { "A previous upload of these bytes is unresolved and will not be repeated." }
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
                        state = "READY",
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
                        message = "Waiting to upload",
                    ),
                )
            }
        } catch (error: Throwable) {
            staging.deleteOwned(staged.ownedFileToken)
            throw error
        }
        if (authorityType == AUTHORITY_AUTOMATIC) runCatching { scheduler.scheduleSoon() }
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
            dao.markLeasedTransferNeedsAttention(
                leased.transferId,
                leaseToken,
                now(),
                "The staged original changed or is missing. Select the photo again.",
            )
            staging.deleteOwned(source.ownedFileToken)
            throw error
        }
        val preUploadToken = try {
            protocol.captureChangeToken(binding)
        } catch (error: Throwable) {
            dao.releaseReadyLease(leased.transferId, leaseToken, now(), "Could not read the iCloud change cursor")
            return 0
        }
        val prepared = try {
            protocol.prepareOriginal(binding, source.displayName, uploadSource, source.lastModifiedAtEpochMillis)
        } catch (error: Throwable) {
            dao.releaseReadyLease(leased.transferId, leaseToken, now(), "Could not prepare the iCloud upload")
            return 0
        }

        val attempt = accountGate(binding.accountId).withLock {
            if (!protocol.isWriteAuthorized() || (leased.authorityType == AUTHORITY_AUTOMATIC && !automaticBackupPolicy.isUnmetered())) {
                prepared.cancel()
                dao.releaseReadyLease(leased.transferId, leaseToken, now(), "Waiting for upload authorization and an eligible network")
                return@withLock null
            }
            val armed = database.withTransaction {
                val active = requireNotNull(dao.findActiveAccount())
                require(active.toBinding() == binding) { "The account changed before upload" }
                val fresh = requireNotNull(dao.findTransfer(leased.transferId))
                if (fresh.state != "READY" || fresh.leaseToken != leaseToken) return@withTransaction null
                val freshSource = requireNotNull(dao.findStagedSource(fresh.stagedSourceId))
                require(freshSource.accountId == binding.accountId &&
                    freshSource.authEpoch == binding.authEpoch &&
                    freshSource.operationFence == binding.operationFence
                ) { "The staged source no longer has write authority" }
                if (fresh.authorityType == AUTHORITY_AUTOMATIC) {
                    val enrollment = requireNotNull(dao.findBackupEnrollment(binding.accountId))
                    require(enrollment.enabled && enrollment.enrollmentId == fresh.enrollmentId &&
                        enrollment.revision == fresh.enrollmentRevision &&
                        enrollment.authorizedAuthEpoch == binding.authEpoch &&
                        enrollment.permissionFingerprint == originals.permissionFingerprint() &&
                        originals.hasAutomaticBackupAccess()
                    ) { "Automatic backup permission or consent changed" }
                }
                check(
                    dao.countActiveSuppressions(
                        binding.accountId,
                        freshSource.lineageKey,
                        freshSource.sha256Hex,
                    ) == 0,
                ) { "This upload is suppressed because an earlier outcome is unresolved" }
                check(dao.findWriteSlot(binding.accountId) == null) { "Another upload is unresolved" }

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
                check(
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
            if (dao.findTransfer(armed.transferId)?.state != "SENDING") {
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
                markUnresolved(transfer, attempt, source, "NOT_OBSERVED",
                    "iCloud could not process this file. The original is still on this phone. This upload will not be repeated.")
                return 1
            }
            if (processing is UploadProcessingStatus.Processing &&
                now() < (transfer.reconciliationDeadlineEpochMillis ?: Long.MAX_VALUE)) {
                dao.markReadFailure(transfer.transferId, attemptId,
                    now() + RECONCILIATION_READ_RETRY_MILLIS, now(),
                    "iCloud is processing this file. The original is still on this phone.")
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
                "Could not check iCloud yet. The upload will not be repeated.",
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
                    "Could not verify an iCloud candidate yet. The upload will not be repeated.",
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
                "iCloud returned an incomplete change page. The upload will not be repeated.",
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
                "AMBIGUOUS",
                "More than one exact iCloud copy matched. Mela will not upload again.",
            )
            now() >= (transfer.reconciliationDeadlineEpochMillis ?: Long.MAX_VALUE) -> markUnresolved(
                transfer,
                attempt,
                source,
                "NOT_OBSERVED",
                "No exact iCloud copy was observed before the check deadline. Mela will not upload again.",
            )
            else -> dao.markReadFailure(
                transfer.transferId,
                attemptId,
                now() + RECONCILIATION_READ_RETRY_MILLIS,
                now(),
                "Still checking iCloud. The upload will not be repeated.",
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
        state: String,
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
                    reason = state,
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
                check(current?.activeAttemptId == attempt.attemptId && current.state != "READY") {
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
                        check(dao.findActiveAccount()?.toBinding() == binding)
                        if (transfer.authorityType == AUTHORITY_AUTOMATIC) {
                            val enrollment = requireNotNull(dao.findEnabledBackupEnrollment(binding.accountId))
                            check(enrollment.enrollmentId == transfer.enrollmentId && enrollment.revision == transfer.enrollmentRevision &&
                                enrollment.authorizedAuthEpoch == binding.authEpoch && originals.hasAutomaticBackupAccess() &&
                                enrollment.permissionFingerprint == originals.permissionFingerprint())
                        }
                        if (transfer.authorityType == AUTHORITY_AUTOMATIC && !automaticBackupPolicy.isUnmetered()) return@withLock
                        val source = requireNotNull(dao.findStagedSource(transfer.stagedSourceId))
                        val prepared = protocol.prepareOriginal(binding, source.displayName,
                            staging.openVerified(source.ownedFileToken, source.byteCount, source.sha256Hex), source.lastModifiedAtEpochMillis)
                        if (!protocol.isWriteAuthorized() || (transfer.authorityType == AUTHORITY_AUTOMATIC && !automaticBackupPolicy.isUnmetered())) {
                            prepared.cancel()
                            return@withLock
                        }
                        if (dao.findTransfer(transfer.transferId)?.state != "SENDING") {
                            prepared.cancel()
                            return@withLock
                        }
                        val acceptance = prepared.startOnce(attemptId)
                        database.withTransaction {
                            dao.insertAcceptance(acceptance.toEntity(attemptId, now()))
                            check(dao.markReconciling(transfer.transferId, attemptId, now(), now() + RECONCILIATION_DEADLINE_MILLIS, now()) == 1)
                        }
                    }
                    return@forEach
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (!protocol.isWriteAuthorized()) return@forEach
                    // A failed action with durable safe state can continue at the next wake.
                    if (error !is IllegalStateException && error !is IllegalArgumentException && protocol.canResume(binding, attemptId)) {
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
        if (page.moreComing) runCatching { scheduler.scheduleSoon() }
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

    private suspend fun disableAutomaticBackup(): BackupView {
        val binding = synchronizeAccount()
        if (binding == null) {
            scheduler.setAutomaticBackupEnabled(false)
            return BackupView(false, message = "Automatic backup is off.")
        }
        val result = accountGate(binding.accountId).withLock {
            database.withTransaction {
                val account = requireNotNull(dao.findActiveAccount())
                val enrollment = dao.findBackupEnrollment(account.accountId)
                val nextEnrollmentFence = (enrollment?.operationFence ?: 0L) + 1L
                dao.disableBackup(account.accountId, nextEnrollmentFence, now())
                dao.markReadyAutomaticTransfersNeedAttention(
                    account.accountId,
                    now(),
                    "Automatic backup was disabled before this upload started.",
                )
                BackupView(false, account.destinationLabel, message = "Automatic backup is off.")
            }
        }
        deleteReleasableStages()
        scheduler.setAutomaticBackupEnabled(false)
        return result
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
                check(dao.countPotentiallyExecutableTrashAttempts() == 0) {
                    "A system trash request may still execute for the previous iCloud account."
                }
                dao.markReadyTransfersNeedAttention(
                    active.accountId,
                    now(),
                    "The authenticated iCloud session changed before upload. Select this photo again.",
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
        if (replacedActiveAccount) runCatching { scheduler.setAutomaticBackupEnabled(false) }
        return binding
    }

    private suspend fun requireActiveBinding(): AccountBinding = synchronizeAccount()
        ?: error("Sign in to iCloud before uploading")

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

    private suspend fun inspectApprovedTrashAttempts() {
        dao.findApprovedTrashAttempts().forEach { inspectTrashAttempt(it.trashAttemptId) }
    }

    private suspend fun inspectTrashAttempt(attemptId: String) {
        val attempt = dao.findTrashAttempt(attemptId) ?: return
        val approved = attempt.consentDisposition == "CALLBACK_OBSERVED_APPROVED"
        val currentBoot = originals.bootSessionId()
        val rebooted = attempt.bootSessionId != null && currentBoot != null && attempt.bootSessionId != currentBoot
        val obligations = dao.findTrashObligations(attemptId)
        var allResolved = obligations.isNotEmpty()
        obligations.forEach { obligation ->
            val state = runCatching { originals.readTrashState(obligation.contentUri) }.getOrNull()
            val resolved = state != null && (rebooted || (approved && state.isTrashed))
            allResolved = allResolved && resolved
            dao.updateTrashObligation(
                attemptId, obligation.mediaId,
                when {
                    state?.isTrashed == true -> "TRASHED_RECOVERABLE"
                    resolved -> "NOT_EXECUTED"
                    else -> "RESULT_UNKNOWN_EXECUTION_STILL_POSSIBLE"
                },
                state?.expiresAtEpochMillis,
                if (resolved) now() else null,
            )
        }
        // An untrashed row is not proof that Android's consent request cannot still execute.
        // Reboot destroys the old PendingIntent; unreadable URIs still require recovery.
        if (allResolved) dao.setTrashDisposition(attemptId, if (rebooted) "TERMINAL_RECOVERED_AFTER_REBOOT" else "TERMINAL_EXECUTED")
    }

    private suspend fun resolveDeniedTrashAttempt(attemptId: String) {
        dao.findTrashObligations(attemptId).forEach { obligation ->
            dao.updateTrashObligation(
                attemptId,
                obligation.mediaId,
                "NOT_EXECUTED",
                null,
                now(),
            )
        }
    }

    private fun blockedProposal(id: String, reason: String): ReclaimProposal = ReclaimProposal(
        id = ReclaimProposalId(id),
        eligibleMediaIds = emptySet(),
        expiresAtEpochMillis = now(),
        blockedReason = reason,
    )

    private fun TransferProjection.toViewState(): TransferViewState = when (state) {
        "READY" -> TransferViewState.WAITING
        "SENDING" -> TransferViewState.UPLOADING
        "OUTCOME_UNKNOWN", "RECONCILING", "READ_FAILURE" -> TransferViewState.CHECKING_ICLOUD
        "VERIFIED" -> TransferViewState.VERIFIED
        "AMBIGUOUS", "NOT_OBSERVED" -> TransferViewState.UNRESOLVED
        "ABANDONED" -> TransferViewState.SKIPPED
        "NEEDS_SIGN_IN" -> TransferViewState.NEEDS_SIGN_IN
        "UNSUPPORTED" -> TransferViewState.UNSUPPORTED
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

    private fun AccountEntity.toBinding() = AccountBinding(accountId, authEpoch, operationFence)

    private fun UploadAcceptance.toEntity(attemptId: String, receivedAt: Long) = UploadAcceptanceEntity(
        attemptId, requestUuid, masterRecordName, assetRecordName, duplicateHint, receivedAt, uploadJobId,
    )

    private fun UploadAcceptanceEntity.toModel() = UploadAcceptance(
        requestUuid, masterRecordName, assetRecordName, duplicateHint, uploadJobId,
    )

    private fun RemoteAssetPairEntity.toCandidate() = RemotePairCandidate(
        master = RemoteRecordRef(masterRecordName, masterChangeTag),
        asset = RemoteRecordRef(assetRecordName, assetChangeTag),
        relationIsCurrent = relationIsCurrent,
        masterDeleted = masterDeleted,
        assetDeleted = assetDeleted,
        originalResourceIdentity = originalResourceIdentity,
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

    private fun automaticBackupPermissions(): List<String> = buildList {
        add(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    private fun stableId(vararg values: String): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private class HashingOutputStream : OutputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        var byteCount: Long = 0
            private set

        override fun write(value: Int) {
            digest.update(value.toByte())
            byteCount += 1
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            digest.update(buffer, offset, length)
            byteCount += length
        }

        fun sha256Hex(): String = digest.cloneDigestHex()

        private fun MessageDigest.cloneDigestHex(): String = (clone() as MessageDigest)
            .digest()
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val AUTHORITY_MANUAL = "MANUAL"
        const val AUTHORITY_AUTOMATIC = "AUTOMATIC"
        const val BACKUP_DISCLOSURE_REVISION = "2026-08-23.1"
        const val BACKUP_DRAFT_LIFETIME_MILLIS = 15 * 60 * 1_000L
        const val READY_LEASE_MILLIS = 2 * 60 * 1_000L
        const val RECONCILIATION_DEADLINE_MILLIS = 15 * 60 * 1_000L
        const val RECONCILIATION_READ_RETRY_MILLIS = 15 * 1_000L
        const val RECONCILIATION_PAGE_LIMIT = 200
        const val AUTO_DISCOVERY_PAGE_SIZE = 1
        const val RECLAIM_PROPOSAL_LIFETIME_MILLIS = 2 * 60 * 1_000L
        val RECONCILABLE_STATES = setOf("OUTCOME_UNKNOWN", "RECONCILING", "READ_FAILURE")
    }
}
