package me.lvca.alba.engine.companion

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import me.lvca.alba.engine.cache.StagedOriginalUnavailableException
import me.lvca.alba.engine.database.AccountWriteSlotEntity
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.AssetLinkEntity
import me.lvca.alba.engine.database.StagedSourceEntity
import me.lvca.alba.engine.database.TransferEntity
import me.lvca.alba.engine.database.TransferState
import me.lvca.alba.engine.database.UploadAcceptanceEntity
import me.lvca.alba.engine.database.UploadAttemptEntity
import me.lvca.alba.engine.database.UploadPauseEntity
import me.lvca.alba.engine.database.UploadUncertaintyEntity
import me.lvca.alba.engine.database.VerificationProofEntity
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.ICloudPhotoProtocol
import me.lvca.alba.engine.model.ICloudStorageFullException
import me.lvca.alba.engine.model.PhotoCompanionException
import me.lvca.alba.engine.model.PreparedICloudUpload
import me.lvca.alba.engine.model.ProtocolArea
import me.lvca.alba.engine.model.ProtocolStatusGate
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.UploadAcceptance
import me.lvca.alba.engine.model.WakeupScheduler
import java.util.UUID

/**
 * Starts uploads, up to [MAX_UPLOADS_IN_FLIGHT] at once per account. Arming an attempt takes one of the account's write
 * slots in the same transaction that moves the transfer to SENDING, so an attempt is sent at most once; anything
 * uncertain after that goes to reconciliation, never to a second upload. Each attempt keeps its own journal row, so a
 * crash with several in flight recovers each on its own.
 */
internal class UploadInvoker(
    private val database: AlbaDatabase,
    /** Where uploads read their originals: staged copies, or the phone's files read in place. */
    private val originals: UploadOriginals,
    private val protocol: ICloudPhotoProtocol,
    private val scheduler: WakeupScheduler,
    private val accounts: AccountBindingSync,
    private val authority: AutomaticBackupAuthority,
    private val now: () -> Long,
    private val stages: UploadStages,
    private val existingCopies: ExistingCopyCheck,
    private val protocolStatus: ProtocolStatusGate = ProtocolStatusGate.Open,
) {
    private val accountDao = database.accountDao()
    private val transferDao = database.transferDao()
    private val verificationDao = database.verificationDao()

    /** What one [runRound] did. */
    data class Round(val invoked: Int, val staged: Int)

    /**
     * Starts as many uploads at once as the account's free write slots allow, up to [MAX_UPLOADS_IN_FLIGHT], and while
     * they upload calls [stageNext] to queue the items after them, so the next pass can start at once. [stageNext] is
     * also asked for an item when nothing is queued yet. Each upload is leased here, one after another, so no two take
     * the same item. A failure of one upload never cancels the others: it is rethrown once all have finished.
     */
    suspend fun runRound(
        binding: AccountBinding,
        allowManual: Boolean,
        allowAutomatic: Boolean,
        stageNext: suspend () -> Int,
    ): Round = supervisorScope {
        // A remote pause leaves every item queued as it is; nothing is sent, so nothing needs checking afterwards.
        if (isProtocolPaused()) return@supervisorScope Round(0, 0)
        var staged = 0
        val uploads = mutableListOf<Deferred<Int>>()
        val free = freeSlots(binding)
        while (uploads.size < free) {
            val leased = leaseNext(binding, allowManual, allowAutomatic)
                ?: stageNext().also { staged += it }.takeIf { it > 0 }?.let { leaseNext(binding, allowManual, allowAutomatic) }
                ?: break
            uploads += async { upload(binding, leased) }
        }
        // While those upload, prepare what comes next, a few items ahead at most.
        while (uploads.isNotEmpty() && aheadOfUploads(binding) < MAX_UPLOADS_IN_FLIGHT) {
            val more = stageNext()
            staged += more
            if (more == 0) break
        }
        val results = uploads.map { runCatching { it.await() } }
        results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        Round(results.sumOf { it.getOrDefault(0) }, staged)
    }

    /** Write slots the account has free for new uploads now; none while iCloud storage is full. */
    suspend fun freeSlots(binding: AccountBinding): Int =
        if (isStoragePaused(binding.accountId)) 0 else (MAX_UPLOADS_IN_FLIGHT - transferDao.countWriteSlots(binding.accountId)).coerceAtLeast(0)

    /** Automatic items queued and not taken by an upload yet: how far discovery is ahead of the uploads. */
    private suspend fun aheadOfUploads(binding: AccountBinding): Int = transferDao.countQueuedAutomatic(binding.accountId, now())

    /** A READY transfer leased for one upload, with its lease token. */
    data class Leased(val transfer: TransferEntity, val leaseToken: String)

    /**
     * Arms and sends [leased], holding one of the account's upload permits while it awaits Apple. Returns 1 when it armed
     * an attempt or linked a copy already in iCloud.
     */
    private suspend fun upload(binding: AccountBinding, leased: Leased): Int = accounts.uploads(binding.accountId).withPermit {
        val transfer = leased.transfer
        val source = requireNotNull(transferDao.findStagedSource(transfer.stagedSourceId))
        lookInLibraryFirst(binding, transfer, leased.leaseToken, source)?.let { return@withPermit it }
        val opened = openForUpload(binding, leased, source) ?: return@withPermit 0
        opened.use { send(binding, leased, source, it.sending) }
    }

    /**
     * Opens [source] for [leased]'s upload. A staged copy that changed or is missing fails the transfer, as before. An
     * original read in place that changed or cannot be read again is read and queued afresh; null then.
     */
    private suspend fun openForUpload(binding: AccountBinding, leased: Leased, source: StagedSourceEntity): OpenedOriginal? {
        // A cancelled open says nothing about the original; it is kept and the lease expires.
        val opened = runSuspendCatching { originals.open(source) }
        val failure = opened.exceptionOrNull() ?: return opened.getOrThrow()
        val changed = failure is SourceChangedException
        transferDao.markLeasedTransferNeedsAttention(
            leased.transfer.transferId,
            leased.leaseToken,
            now(),
            if (changed) {
                StoredMessage.THIS_PHOTO_CHANGED_WHILE_IT_WAS_BEING_UPLOADED.code
            } else {
                StoredMessage.THE_STAGED_ORIGINAL_CHANGED_OR_IS_MISSING_SELECT_THE_PHOTO_AGAIN.code
            },
        )
        if (!changed) {
            originals.discardStaged(source)
            throw failure
        }
        redo(binding, leased.transfer.transferId)
        return null
    }

    /** An upload prepared with the protocol, and the change cursor read before it. */
    private class Prepared(val upload: PreparedICloudUpload, val preUploadToken: String)

    /** What arming under the account gate decided: send [attempt], or stop, reporting [invoked]. */
    private sealed interface Armed {
        class Send(val attempt: UploadAttemptEntity) : Armed

        class Done(val invoked: Int) : Armed
    }

    /** Prepares, arms and sends one attempt for [leased]. Returns 1 when an attempt was armed. */
    private suspend fun send(binding: AccountBinding, leased: Leased, source: StagedSourceEntity, sending: SentBytesSource): Int {
        val prepared = prepare(binding, leased, source, sending) ?: return 0
        val armed = when (val decided = armUnderGate(binding, leased, source.isVideo, prepared)) {
            is Armed.Send -> decided.attempt
            is Armed.Done -> return decided.invoked
        }
        // The gate is free again, so other uploads arm while this one awaits Apple; the permit bounds how many.
        sendArmed(binding, leased.transfer, armed, prepared.upload, sending)
        return 1
    }

    /** Reads the change cursor and prepares the upload; null, with the lease released, when either fails. */
    private suspend fun prepare(binding: AccountBinding, leased: Leased, source: StagedSourceEntity, sending: SentBytesSource): Prepared? {
        val transferId = leased.transfer.transferId
        val preUploadToken = runSuspendCatching { protocol.captureChangeToken(binding) }.getOrElse {
            transferDao.releaseReadyLease(transferId, leased.leaseToken, now(), StoredMessage.COULD_NOT_READ_THE_ICLOUD_CHANGE_CURSOR.code)
            return null
        }
        val upload = runSuspendCatching {
            protocol.prepareOriginal(binding, source.displayName, sending, source.uploadDate, source.captureOffsetMinutes)
        }.getOrElse {
            transferDao.releaseReadyLease(transferId, leased.leaseToken, now(), StoredMessage.COULD_NOT_PREPARE_THE_ICLOUD_UPLOAD.code)
            return null
        }
        return Prepared(upload, preUploadToken)
    }

    /** Arms one attempt under the account gate, or lets the leased transfer go when it may not be sent now. */
    private suspend fun armUnderGate(binding: AccountBinding, leased: Leased, video: Boolean, prepared: Prepared): Armed {
        val transfer = leased.transfer
        return accounts.gate(binding.accountId).withLock {
            val armed = if (canWriteNow(transfer.authorityType, video)) {
                arm(binding, transfer.transferId, leased.leaseToken, prepared.preUploadToken)
            } else {
                transferDao.releaseReadyLease(
                    transfer.transferId, leased.leaseToken, now(), StoredMessage.WAITING_FOR_UPLOAD_AUTHORIZATION_AND_AN_ELIGIBLE_NETWORK.code,
                )
                null
            }
            // Neither release below has sent anything for this attempt: startOnce has not run, so no request of it left
            // the phone. The attempt is let go at once rather than verified for fifteen minutes holding a write slot.
            val decided = when {
                armed == null -> Armed.Done(0)
                !isStillSending(armed.transferId) -> Armed.Done(1)
                authority.waitsForNetwork(transfer.authorityType, video) -> {
                    releaseUnsent(binding, armed, TransferState.READY, StoredMessage.WAITING_FOR_UPLOAD_AUTHORIZATION_AND_AN_ELIGIBLE_NETWORK)
                    Armed.Done(1)
                }
                transfer.authorityType == AUTHORITY_AUTOMATIC &&
                    !authority.stillAuthorizes(transfer.enrollmentId, transfer.enrollmentRevision, binding) -> {
                    releaseUnsent(binding, armed, TransferState.NEEDS_ATTENTION, StoredMessage.AUTOMATIC_BACKUP_PERMISSION_OR_CONSENT_CHANGED)
                    Armed.Done(1)
                }
                else -> Armed.Send(armed)
            }
            if (decided is Armed.Done) prepared.upload.cancel()
            decided
        }
    }

    /** Sends [armed] once. Anything uncertain afterwards goes to verification; an original that changed is redone. */
    private suspend fun sendArmed(
        binding: AccountBinding,
        transfer: TransferEntity,
        armed: UploadAttemptEntity,
        prepared: PreparedICloudUpload,
        sending: SentBytesSource,
    ) {
        try {
            sendOnce(prepared, sending, armed.transferId, armed.attemptId)
        } catch (full: ICloudStorageFullException) {
            prepared.cancel()
            storageRefused(binding, armed, full)
        } catch (error: Throwable) {
            prepared.cancel()
            when {
                error.isSourceChange() && releaseChanged(binding, armed) -> Unit
                !protocol.canResume(binding, armed.attemptId) ->
                    recordUnknown(armed, error::class.java.simpleName.ifBlank { "NO_TRUSTED_RESPONSE" })
                transfer.authorityType == AUTHORITY_AUTOMATIC -> scheduler.scheduleSoon()
            }
            if (error is CancellationException) throw error
        }
    }

    /**
     * [armed]'s original changed while it was sent, so the upload stopped before its last bytes. When the protocol's
     * journal proves nothing of it was registered, the attempt is let go and the item is read and queued afresh; returns
     * true. Otherwise it returns false and the attempt goes to verification like any uncertain one.
     */
    private suspend fun releaseChanged(binding: AccountBinding, armed: UploadAttemptEntity): Boolean {
        if (!protocol.registrationNeverSent(binding, armed.attemptId)) return false
        releaseUnsent(binding, armed, TransferState.NEEDS_ATTENTION, StoredMessage.THIS_PHOTO_CHANGED_WHILE_IT_WAS_BEING_UPLOADED, "SOURCE_CHANGED")
        redo(binding, armed.transferId)
        return true
    }

    /** Reads and queues [transferId] again after its original changed; it keeps its reason when that fails. */
    private suspend fun redo(binding: AccountBinding, transferId: String) {
        if (originals.restage(transferId) && accounts.isCurrent(binding)) runSuspendCatching { scheduler.scheduleSoon() }
    }

    /** Leases the next READY transfer the account may start now; null when there is none. */
    private suspend fun leaseNext(binding: AccountBinding, allowManual: Boolean, allowAutomatic: Boolean): Leased? {
        if (isStoragePaused(binding.accountId)) return null
        val candidate = transferDao.findNextReady(
            binding.accountId,
            now(),
            allowAutomaticPhotos = allowAutomatic && authority.canRun(binding, video = false),
            allowAutomaticVideos = allowAutomatic && authority.canRun(binding, video = true),
            allowManual = allowManual,
        ) ?: return null
        val leaseToken = UUID.randomUUID().toString()
        val leaseNow = now()
        val claimed = transferDao.claimReady(
            candidate.transferId,
            candidate.stateVersion,
            leaseToken,
            leaseNow + READY_LEASE_MILLIS,
            leaseNow,
        ) == 1
        return if (claimed) Leased(requireNotNull(transferDao.findTransfer(candidate.transferId)), leaseToken) else null
    }

    /**
     * Looks for an automatic backup item in the iCloud library before its first upload. Returns null when it should be
     * uploaded now, or what [upload] returns when it was linked to a copy already there (1), must wait, or lost its
     * lease to another pass (0). The lease is extended before each download, which can outlast [READY_LEASE_MILLIS].
     */
    private suspend fun lookInLibraryFirst(binding: AccountBinding, leased: TransferEntity, leaseToken: String, source: StagedSourceEntity): Int? {
        if (leased.authorityType != AUTHORITY_AUTOMATIC || leased.existingCopyCheck != null) return null
        val enrolledAt = authority.currentEnrollment(binding)?.consentedAtEpochMillis
        val keepLease: suspend () -> Boolean = {
            transferDao.extendReadyLease(leased.transferId, leaseToken, now() + ExistingCopyCheck.COMPARE_LEASE_MILLIS) == 1
        }
        return when (val found = existingCopies.check(binding, leased, source, enrolledAt, keepLease)) {
            is ExistingCopyOutcome.Wait -> {
                transferDao.deferReadyLease(leased.transferId, leaseToken, found.retryAt, now(), found.message.code, found.firstFailureAt)
                runSuspendCatching { scheduler.scheduleAt(found.retryAt) }
                0
            }
            is ExistingCopyOutcome.Matched -> linkExisting(binding, leased, leaseToken, source, found)
            ExistingCopyOutcome.LeaseLost -> 0
            // A lease lost meanwhile leaves the transfer to whoever holds it now.
            is ExistingCopyOutcome.Upload ->
                if (transferDao.noteExistingCopyCheck(leased.transferId, leaseToken, found.result, now()) == 1) null else 0
        }
    }

    /** Why [linkExisting] did not link. */
    private enum class LinkRefusal { LEASE_LOST, NOT_AUTHORIZED }

    /**
     * Backs [transfer] up without uploading: the library item in [found] holds its exact bytes. It is linked and proven
     * the way a verified upload is, with a link named after the transfer since no attempt was made, so the photo counts
     * as backed up and Free up space re-checks both copies before it offers to delete the phone's. Returns 1 when linked,
     * 0 when the lease was lost (nothing is written) or backup no longer authorizes it.
     */
    private suspend fun linkExisting(
        binding: AccountBinding,
        transfer: TransferEntity,
        leaseToken: String,
        source: StagedSourceEntity,
        found: ExistingCopyOutcome.Matched,
    ): Int {
        val pair = found.pair
        val linkedAt = now()
        val pairId = remotePairId(binding, pair)
        val linkId = UUID.randomUUID().toString()
        val refusal = database.withTransaction {
            if (!accounts.isCurrent(binding) ||
                !authority.stillAuthorizes(transfer.enrollmentId, transfer.enrollmentRevision, binding)
            ) return@withTransaction LinkRefusal.NOT_AUTHORIZED
            // Claimed first: when another pass holds the transfer now, nothing below is written.
            val marked = transferDao.markAlreadyInICloud(
                transfer.transferId, leaseToken, linkedAt, ExistingCopyCheck.RESULT_MATCHED, StoredMessage.ALREADY_IN_ICLOUD.code,
            )
            if (marked != 1) return@withTransaction LinkRefusal.LEASE_LOST
            verificationDao.insertRemotePair(pair.toPairEntity(binding, linkedAt))
            verificationDao.insertAssetLink(
                AssetLinkEntity(
                    linkId, binding.accountId, transfer.mediaId, source.stagedSourceId,
                    pairId, "$EXISTING_LINK_PREFIX${transfer.transferId}", linkedAt, "CURRENT",
                ),
            )
            verificationDao.insertVerificationProof(
                VerificationProofEntity(
                    proofId = UUID.randomUUID().toString(),
                    accountId = binding.accountId,
                    verificationAuthEpoch = binding.authEpoch,
                    invocationAuthEpoch = binding.authEpoch,
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
                    verifiedAtEpochMillis = linkedAt,
                    invalidatedAtEpochMillis = null,
                    invalidationReason = null,
                    // The existing copy was downloaded and compared byte for byte before it was linked.
                    method = VerificationProofEntity.METHOD_BYTES,
                    byteCheckedAtEpochMillis = linkedAt,
                ),
            )
            null
        }
        return when (refusal) {
            null -> {
                stages.release(source)
                runSuspendCatching { scheduler.scheduleSoon() }
                1
            }
            LinkRefusal.LEASE_LOST -> 0
            LinkRefusal.NOT_AUTHORIZED -> {
                transferDao.releaseReadyLease(transfer.transferId, leaseToken, now(), StoredMessage.AUTOMATIC_BACKUP_PERMISSION_OR_CONSENT_CHANGED.code)
                0
            }
        }
    }

    /**
     * Finishes attempts a process death left in SENDING. One the protocol can resume under the current binding
     * continues; anything else becomes uncertain and goes to reconciliation. Returns how many it found.
     */
    suspend fun recoverInterrupted(binding: AccountBinding?, allowManual: Boolean, allowAutomatic: Boolean): Int {
        if (binding == null || !protocol.isWriteAuthorized()) return 0
        val interrupted = transferDao.findInterruptedSending()
        interrupted.forEach { transfer ->
            val attemptId = requireNotNull(transfer.activeAttemptId)
            val observedAt = now()
            if (transfer.authorityType == AUTHORITY_AUTOMATIC && !allowAutomatic) return@forEach
            if (authority.waitsForNetwork(transfer.authorityType, isVideo(transfer))) return@forEach
            if (transfer.accountId == binding.accountId && transfer.authEpoch == binding.authEpoch &&
                transfer.operationFence == binding.operationFence && protocol.canResume(binding, attemptId)) {
                if (transfer.authorityType == AUTHORITY_MANUAL && !allowManual) return@forEach
                if (!isStoragePaused(binding.accountId) && !isProtocolPaused()) resumeOrSettle(binding, transfer, attemptId)
                return@forEach
            }
            database.withTransaction {
                transferDao.insertUncertainty(
                    UploadUncertaintyEntity(attemptId, "PROCESS_INTERRUPTED_AFTER_AUTHORIZATION", observedAt),
                )
                transferDao.markOutcomeUnknown(
                    transfer.transferId,
                    attemptId,
                    observedAt,
                    observedAt + RECONCILIATION_DEADLINE_MILLIS,
                )
            }
        }
        return interrupted.size
    }

    /**
     * Resumes an interrupted attempt under its own id. A failure that may pass on retry, with durable safe state, waits
     * for the next wake; a storage refusal pauses uploads; anything else becomes uncertain and goes to verification.
     */
    private suspend fun resumeOrSettle(binding: AccountBinding, transfer: TransferEntity, attemptId: String) {
        val attempt = requireNotNull(transferDao.findAttempt(attemptId))
        try {
            resume(binding, transfer, attemptId)
        } catch (full: ICloudStorageFullException) {
            storageRefused(binding, attempt, full)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (!protocol.isWriteAuthorized()) return
            // An original read in place that changed is read and queued afresh once nothing of it can be registered.
            if (error.isSourceChange() && releaseChanged(binding, attempt)) return
            if (!error.failsEveryRetry() && protocol.canResume(binding, attemptId)) {
                if (transfer.authorityType == AUTHORITY_AUTOMATIC) scheduler.scheduleSoon()
                return
            }
            recordUnknown(attempt, "RESUME_INTERRUPTED")
        }
    }

    /**
     * Stops the user's queued uploads and marks any in flight as uncertain. It takes no account or maintenance
     * lock, because an upload holds them while awaiting Apple; the stop commits before that coroutine is cancelled.
     */
    suspend fun cancelManual() {
        database.withTransaction {
            val account = accountDao.findActiveAccount() ?: return@withTransaction
            transferDao.cancelReadyManualTransfers(account.accountId, now())
            transferDao.findInterruptedSending().filter { it.accountId == account.accountId && it.authorityType == AUTHORITY_MANUAL }
                .forEach { transfer ->
                    val attemptId = requireNotNull(transfer.activeAttemptId)
                    transferDao.insertUncertainty(UploadUncertaintyEntity(attemptId, "USER_CANCELLED", now()))
                    transferDao.markOutcomeUnknown(transfer.transferId, attemptId, now(), now() + RECONCILIATION_DEADLINE_MILLIS)
                }
        }
    }

    /** Authorizes one attempt for the leased transfer and takes the write slot, or returns null if the lease is gone. */
    private suspend fun arm(
        binding: AccountBinding,
        transferId: String,
        leaseToken: String,
        preUploadToken: String,
    ): UploadAttemptEntity? = database.withTransaction {
        refuseUnless(accounts.isCurrent(binding), StoredMessage.THE_ACCOUNT_CHANGED_BEFORE_UPLOAD)
        val fresh = requireNotNull(transferDao.findTransfer(transferId))
        if (fresh.state != TransferState.READY || fresh.leaseToken != leaseToken) return@withTransaction null
        val freshSource = requireNotNull(transferDao.findStagedSource(fresh.stagedSourceId))
        refuseUnless(freshSource.accountId == binding.accountId &&
            freshSource.authEpoch == binding.authEpoch &&
            freshSource.operationFence == binding.operationFence,
            StoredMessage.THE_STAGED_SOURCE_NO_LONGER_HAS_WRITE_AUTHORITY,
        )
        if (fresh.authorityType == AUTHORITY_AUTOMATIC) {
            refuseUnless(
                authority.stillAuthorizes(fresh.enrollmentId, fresh.enrollmentRevision, binding),
                StoredMessage.AUTOMATIC_BACKUP_PERMISSION_OR_CONSENT_CHANGED,
            )
        }
        refuseUnless(
            transferDao.countActiveSuppressions(
                binding.accountId,
                freshSource.lineageKey,
                freshSource.sha256Hex,
            ) == 0,
            StoredMessage.THIS_UPLOAD_IS_SUPPRESSED_BECAUSE_AN_EARLIER_OUTCOME_IS_UNRESOLVED,
        )
        refuseUnless(transferDao.countWriteSlots(binding.accountId) < MAX_UPLOADS_IN_FLIGHT, StoredMessage.ANOTHER_UPLOAD_IS_UNRESOLVED)

        val attemptId = UUID.randomUUID().toString()
        val authorizedAt = now()
        // A transfer whose earlier attempt was proven not received starts a fresh one that names it.
        val predecessor = transferDao.findLatestAttemptId(fresh.transferId)
        transferDao.insertAttempt(
            UploadAttemptEntity(
                attemptId = attemptId,
                transferId = fresh.transferId,
                stagedSourceId = fresh.stagedSourceId,
                accountId = binding.accountId,
                authEpoch = binding.authEpoch,
                operationFence = binding.operationFence,
                preUploadChangeToken = preUploadToken,
                predecessorAttemptId = predecessor,
                authorizedAtEpochMillis = authorizedAt,
            ),
        )
        transferDao.acquireWriteSlot(
            AccountWriteSlotEntity(binding.accountId, attemptId, binding.authEpoch, authorizedAt),
        )
        fenceHolds(
            transferDao.armSending(
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
            binding.authEpoch, binding.operationFence, preUploadToken, predecessor, authorizedAt,
        )
    }

    /**
     * Re-sends an interrupted attempt under the same attempt id; the protocol's journal makes this idempotent. It checks
     * under the account gate and sends holding an upload permit, as a first upload does.
     */
    private suspend fun resume(binding: AccountBinding, transfer: TransferEntity, attemptId: String) = accounts.uploads(binding.accountId).withPermit {
        val opened = accounts.gate(binding.accountId).withLock {
            refuseUnless(accounts.isCurrent(binding), StoredMessage.THE_ACCOUNT_CHANGED_BEFORE_UPLOAD)
            if (transfer.authorityType == AUTHORITY_AUTOMATIC) {
                refuseUnless(
                    authority.stillAuthorizes(transfer.enrollmentId, transfer.enrollmentRevision, binding),
                    StoredMessage.AUTOMATIC_BACKUP_PERMISSION_OR_CONSENT_CHANGED,
                )
            }
            val source = refuseIfNull(
                transferDao.findStagedSource(transfer.stagedSourceId),
                StoredMessage.THE_STAGED_ORIGINAL_CHANGED_OR_IS_MISSING_SELECT_THE_PHOTO_AGAIN,
            )
            if (authority.waitsForNetwork(transfer.authorityType, source.isVideo)) return@withPermit
            source to openStaged(source)
        }
        val (source, original) = opened
        original.use {
            val prepared = protocol.prepareOriginal(binding, source.displayName, it.sending, source.uploadDate, source.captureOffsetMinutes)
            if (!canWriteNow(transfer.authorityType, source.isVideo) || !isStillSending(transfer.transferId)) {
                prepared.cancel()
                return@withPermit
            }
            sendOnce(prepared, it.sending, transfer.transferId, attemptId)
        }
    }

    /**
     * Sends the prepared bytes once and records Apple's acceptance with its receipt and what was sent; the transfer then
     * waits for reconciliation, which matches the receipt in iCloud's change feed.
     */
    private suspend fun sendOnce(prepared: PreparedICloudUpload, sending: SentBytesSource, transferId: String, attemptId: String) {
        val acceptance = prepared.startOnce(attemptId)
        database.withTransaction {
            // Apple accepted an upload, so iCloud has space again: lift a storage pause.
            transferDao.findTransfer(transferId)?.let { transferDao.clearUploadPause(it.accountId) }
            transferDao.insertAcceptance(acceptance.toEntity(attemptId, now(), sending.sent))
            fenceHolds(
                transferDao.markReconciling(
                    transferId,
                    attemptId,
                    dueAt = now(),
                    deadline = now() + RECONCILIATION_DEADLINE_MILLIS,
                    now = now(),
                ) == 1,
            )
        }
    }

    /** Opens [source] for a resumed upload: in place, or its staged copy. An in-place original that changed throws. */
    private suspend fun openStaged(source: StagedSourceEntity): OpenedOriginal = try {
        originals.open(source)
    } catch (_: StagedOriginalUnavailableException) {
        throw PhotoCompanionException.Refused(StoredMessage.THE_STAGED_ORIGINAL_CHANGED_OR_IS_MISSING_SELECT_THE_PHOTO_AGAIN)
    }

    private fun canWriteNow(authorityType: String, video: Boolean): Boolean =
        protocol.isWriteAuthorized() && !authority.waitsForNetwork(authorityType, video)

    private suspend fun isVideo(transfer: TransferEntity): Boolean =
        transferDao.findStagedSource(transfer.stagedSourceId)?.isVideo == true

    /**
     * Whether Alba's remote protocol status pauses uploads, for example while an iCloud change is investigated. Queued
     * items stay queued, an interrupted upload stays resumable, and both continue on the first pass after it clears.
     */
    suspend fun isProtocolPaused(): Boolean = protocolStatus.pauseFor(ProtocolArea.UPLOADS) != null

    /** Whether iCloud refused an upload for storage and neither the user nor the retry time has lifted the pause. */
    suspend fun isStoragePaused(accountId: String): Boolean {
        val pause = transferDao.findUploadPause(accountId) ?: return false
        if (pause.retryAtEpochMillis > now()) return true
        // The retry time passed: the next upload tries again, and the status stops saying iCloud is full.
        transferDao.clearUploadPause(accountId)
        return false
    }

    /**
     * Apple refused [attempt] for lack of storage. A refused reservation comes before any byte is sent, so the attempt
     * stays resumable; a refused registration comes after the bytes, so that attempt goes to verification. Either way
     * every upload waits rather than failing one by one.
     */
    private suspend fun storageRefused(binding: AccountBinding, attempt: UploadAttemptEntity, full: ICloudStorageFullException) {
        if (!protocol.canResume(binding, attempt.attemptId)) recordUnknown(attempt, full::class.java.simpleName)
        pauseForStorageFull(binding.accountId, attempt.transferId)
    }

    private suspend fun pauseForStorageFull(accountId: String, transferId: String) {
        val pausedAt = now()
        database.withTransaction {
            transferDao.pauseUploads(
                UploadPauseEntity(accountId, StoredMessage.ICLOUD_STORAGE_FULL.code, pausedAt, pausedAt + STORAGE_FULL_RETRY_MILLIS),
            )
            transferDao.noteSendingMessage(transferId, StoredMessage.ICLOUD_STORAGE_FULL.code, pausedAt)
        }
        runSuspendCatching { scheduler.scheduleAt(pausedAt + STORAGE_FULL_RETRY_MILLIS) }
    }

    private suspend fun isStillSending(transferId: String): Boolean =
        transferDao.findTransfer(transferId)?.state == TransferState.SENDING

    /**
     * Lets go of [attempt], armed but never sent: the transfer goes to [state] without an attempt and the write slot is
     * freed. The attempt row stays, and the next attempt names it as its predecessor.
     */
    private suspend fun releaseUnsent(
        binding: AccountBinding,
        attempt: UploadAttemptEntity,
        state: TransferState,
        message: StoredMessage,
        reason: String = "RELEASED_BEFORE_SEND",
    ) {
        database.withTransaction {
            transferDao.insertUncertainty(UploadUncertaintyEntity(attempt.attemptId, reason, now()))
            check(transferDao.releaseUnsentAttempt(attempt.transferId, attempt.attemptId, state, now(), message.code) == 1)
            check(transferDao.releaseWriteSlot(binding.accountId, attempt.attemptId) == 1)
        }
    }

    private suspend fun recordUnknown(attempt: UploadAttemptEntity, reason: String) {
        val observedAt = now()
        database.withTransaction {
            transferDao.insertUncertainty(UploadUncertaintyEntity(attempt.attemptId, reason, observedAt))
            val changed = transferDao.markOutcomeUnknown(
                attempt.transferId,
                attempt.attemptId,
                observedAt,
                observedAt + RECONCILIATION_DEADLINE_MILLIS,
            )
            if (changed == 0) {
                // Notification cancellation may have already stopped this attempt.
                val current = transferDao.findTransfer(attempt.transferId)
                check(current?.activeAttemptId == attempt.attemptId && current.state != TransferState.READY) {
                    "The uncertain upload no longer owns its transfer"
                }
            }
        }
    }

    private fun UploadAcceptance.toEntity(attemptId: String, receivedAt: Long, sent: SentBytes?) = UploadAcceptanceEntity(
        attemptId, requestUuid, masterRecordName, assetRecordName, duplicateHint, receivedAt, uploadJobId,
        receiptByteCount = receipt?.byteCount,
        receiptFileChecksum = receipt?.fileChecksum,
        sentByteCount = sent?.byteCount,
        sentSha256Hex = sent?.sha256Hex,
    )

    private companion object {
        const val READY_LEASE_MILLIS = 2 * 60 * 1_000L

        /** Names the link of an item found already in iCloud, which has no upload attempt of its own. */
        const val EXISTING_LINK_PREFIX = "existing:"

        /** Background passes try a storage-refused upload again after this long; the user can retry sooner. */
        const val STORAGE_FULL_RETRY_MILLIS = 6 * 60 * 60 * 1_000L
    }
}

/**
 * Alba's refusals and the protocol's own check/require failures (a changed source file, an unexpected
 * upload host) fail the same way on every attempt, so resuming would loop and hold the account's write slot.
 */
private fun Throwable.failsEveryRetry(): Boolean =
    isFinalRefusal() || this is IllegalStateException || this is IllegalArgumentException

/** Whether this failure, or one it wraps, is an original that changed while it was being sent. */
private fun Throwable.isSourceChange(): Boolean = generateSequence(this) { it.cause }.any { it is SourceChangedException }

/**
 * The date iCloud is given for the original: its capture time, or, for a source staged before capture times were kept,
 * its modification date as before, so a resumed upload registers the same date it saved.
 */
internal val StagedSourceEntity.uploadDate: Long? get() = capturedAtEpochMillis ?: lastModifiedAtEpochMillis

/** Staged videos follow the video setting for mobile data; everything else counts as a photo. */
internal val StagedSourceEntity.isVideo: Boolean get() = mimeType.startsWith("video/")
