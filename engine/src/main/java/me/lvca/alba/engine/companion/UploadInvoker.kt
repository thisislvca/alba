package me.lvca.alba.engine.companion

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import me.lvca.alba.engine.cache.StagedOriginalUnavailableException
import me.lvca.alba.engine.cache.UploadStagingStore
import me.lvca.alba.engine.database.AccountWriteSlotEntity
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.StagedSourceEntity
import me.lvca.alba.engine.database.TransferEntity
import me.lvca.alba.engine.database.TransferState
import me.lvca.alba.engine.database.UploadAcceptanceEntity
import me.lvca.alba.engine.database.UploadAttemptEntity
import me.lvca.alba.engine.database.UploadUncertaintyEntity
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.ICloudPhotoProtocol
import me.lvca.alba.engine.model.OneShotUploadSource
import me.lvca.alba.engine.model.PhotoCompanionException
import me.lvca.alba.engine.model.PreparedICloudUpload
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.UploadAcceptance
import me.lvca.alba.engine.model.WakeupScheduler
import java.util.UUID

/**
 * Starts uploads one at a time per account. Arming an attempt takes the account's write slot in the same
 * transaction that moves the transfer to SENDING, so an attempt is sent at most once; anything uncertain after
 * that goes to reconciliation, never to a second upload.
 */
internal class UploadInvoker(
    private val database: AlbaDatabase,
    private val staging: UploadStagingStore,
    private val protocol: ICloudPhotoProtocol,
    private val scheduler: WakeupScheduler,
    private val accounts: AccountBindingSync,
    private val authority: AutomaticBackupAuthority,
    private val now: () -> Long,
) {
    private val accountDao = database.accountDao()
    private val transferDao = database.transferDao()

    /** Leases, arms and sends the next READY transfer. Returns 1 when it armed an attempt. */
    suspend fun invokeOne(binding: AccountBinding, allowManual: Boolean, allowAutomatic: Boolean): Int {
        if (transferDao.findWriteSlot(binding.accountId) != null) return 0
        val candidate = transferDao.findNextReady(binding.accountId, now(), allowAutomatic && authority.canRun(binding), allowManual) ?: return 0
        val leaseToken = UUID.randomUUID().toString()
        val leaseNow = now()
        if (transferDao.claimReady(
                candidate.transferId,
                candidate.stateVersion,
                leaseToken,
                leaseNow + READY_LEASE_MILLIS,
                leaseNow,
            ) != 1
        ) return 0
        val leased = requireNotNull(transferDao.findTransfer(candidate.transferId))
        val source = requireNotNull(transferDao.findStagedSource(leased.stagedSourceId))

        val uploadSource = try {
            staging.openVerified(source.ownedFileToken, source.byteCount, source.sha256Hex)
        } catch (error: Throwable) {
            // A cancelled check says nothing about the staged file; keep it and let the lease expire.
            if (error is CancellationException) throw error
            transferDao.markLeasedTransferNeedsAttention(
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
            transferDao.releaseReadyLease(leased.transferId, leaseToken, now(), StoredMessage.COULD_NOT_READ_THE_ICLOUD_CHANGE_CURSOR.code)
            return 0
        }
        val prepared = try {
            protocol.prepareOriginal(binding, source.displayName, uploadSource, source.lastModifiedAtEpochMillis)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            transferDao.releaseReadyLease(leased.transferId, leaseToken, now(), StoredMessage.COULD_NOT_PREPARE_THE_ICLOUD_UPLOAD.code)
            return 0
        }

        val attempt = accounts.gate(binding.accountId).withLock {
            if (!canWriteNow(leased.authorityType)) {
                prepared.cancel()
                transferDao.releaseReadyLease(leased.transferId, leaseToken, now(), StoredMessage.WAITING_FOR_UPLOAD_AUTHORIZATION_AND_AN_ELIGIBLE_NETWORK.code)
                return@withLock null
            }
            val armed = arm(binding, leased.transferId, leaseToken, preUploadToken)
            if (armed == null) {
                prepared.cancel()
                return@withLock null
            }
            if (!isStillSending(armed.transferId)) {
                prepared.cancel()
                return@withLock armed
            }

            if (authority.waitsForNetwork(leased.authorityType)) {
                prepared.cancel()
                recordUnknown(armed, "NETWORK_CHANGED_AFTER_COMMIT")
                return@withLock armed
            }
            if (leased.authorityType == AUTHORITY_AUTOMATIC &&
                !authority.stillAuthorizes(leased.enrollmentId, leased.enrollmentRevision, binding)
            ) {
                recordUnknown(armed, "AUTHORITY_CHANGED_AFTER_COMMIT")
                return@withLock armed
            }

            try {
                send(prepared, armed.transferId, armed.attemptId)
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
            if (authority.waitsForNetwork(transfer.authorityType)) return@forEach
            if (transfer.accountId == binding.accountId && transfer.authEpoch == binding.authEpoch &&
                transfer.operationFence == binding.operationFence && protocol.canResume(binding, attemptId)) {
                if (transfer.authorityType == AUTHORITY_MANUAL && !allowManual) return@forEach
                val attempt = requireNotNull(transferDao.findAttempt(attemptId))
                try {
                    resume(binding, transfer, attemptId)
                    return@forEach
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (!protocol.isWriteAuthorized()) return@forEach
                    // Only failures that may pass on retry, with durable safe state, continue at the next wake.
                    if (!error.failsEveryRetry() && protocol.canResume(binding, attemptId)) {
                        if (transfer.authorityType == AUTHORITY_AUTOMATIC) scheduler.scheduleSoon()
                        return@forEach
                    }
                    recordUnknown(attempt, "RESUME_INTERRUPTED")
                    return@forEach
                }
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
        refuseUnless(transferDao.findWriteSlot(binding.accountId) == null, StoredMessage.ANOTHER_UPLOAD_IS_UNRESOLVED)

        val attemptId = UUID.randomUUID().toString()
        val authorizedAt = now()
        transferDao.insertAttempt(
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
            binding.authEpoch, binding.operationFence, preUploadToken, null, authorizedAt,
        )
    }

    /** Re-sends an interrupted attempt under the same attempt id; the protocol's journal makes this idempotent. */
    private suspend fun resume(binding: AccountBinding, transfer: TransferEntity, attemptId: String) {
        accounts.gate(binding.accountId).withLock {
            refuseUnless(accounts.isCurrent(binding), StoredMessage.THE_ACCOUNT_CHANGED_BEFORE_UPLOAD)
            if (transfer.authorityType == AUTHORITY_AUTOMATIC) {
                refuseUnless(
                    authority.stillAuthorizes(transfer.enrollmentId, transfer.enrollmentRevision, binding),
                    StoredMessage.AUTOMATIC_BACKUP_PERMISSION_OR_CONSENT_CHANGED,
                )
            }
            if (authority.waitsForNetwork(transfer.authorityType)) return@withLock
            val source = refuseIfNull(
                transferDao.findStagedSource(transfer.stagedSourceId),
                StoredMessage.THE_STAGED_ORIGINAL_CHANGED_OR_IS_MISSING_SELECT_THE_PHOTO_AGAIN,
            )
            val prepared = protocol.prepareOriginal(binding, source.displayName, openStaged(source), source.lastModifiedAtEpochMillis)
            if (!canWriteNow(transfer.authorityType) || !isStillSending(transfer.transferId)) {
                prepared.cancel()
                return@withLock
            }
            send(prepared, transfer.transferId, attemptId)
        }
    }

    /** Sends the prepared bytes once and records Apple's acceptance; the transfer then waits for reconciliation. */
    private suspend fun send(prepared: PreparedICloudUpload, transferId: String, attemptId: String) {
        val acceptance = prepared.startOnce(attemptId)
        database.withTransaction {
            transferDao.insertAcceptance(acceptance.toEntity(attemptId, now()))
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

    private suspend fun openStaged(source: StagedSourceEntity): OneShotUploadSource = try {
        staging.openVerified(source.ownedFileToken, source.byteCount, source.sha256Hex)
    } catch (_: StagedOriginalUnavailableException) {
        throw PhotoCompanionException.Refused(StoredMessage.THE_STAGED_ORIGINAL_CHANGED_OR_IS_MISSING_SELECT_THE_PHOTO_AGAIN)
    }

    private fun canWriteNow(authorityType: String): Boolean =
        protocol.isWriteAuthorized() && !authority.waitsForNetwork(authorityType)

    private suspend fun isStillSending(transferId: String): Boolean =
        transferDao.findTransfer(transferId)?.state == TransferState.SENDING

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

    private fun UploadAcceptance.toEntity(attemptId: String, receivedAt: Long) = UploadAcceptanceEntity(
        attemptId, requestUuid, masterRecordName, assetRecordName, duplicateHint, receivedAt, uploadJobId,
    )

    private companion object {
        const val READY_LEASE_MILLIS = 2 * 60 * 1_000L
    }
}

/**
 * Alba's refusals and the protocol's own check/require failures (a changed source file, an unexpected
 * upload host) fail the same way on every attempt, so resuming would loop and hold the account's write slot.
 */
private fun Throwable.failsEveryRetry(): Boolean =
    isFinalRefusal() || this is IllegalStateException || this is IllegalArgumentException
