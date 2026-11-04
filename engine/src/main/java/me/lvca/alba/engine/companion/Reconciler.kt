package me.lvca.alba.engine.companion

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.AssetLinkEntity
import me.lvca.alba.engine.database.ReconciliationMatchEntity
import me.lvca.alba.engine.database.StagedSourceEntity
import me.lvca.alba.engine.database.TransferEntity
import me.lvca.alba.engine.database.TransferState
import me.lvca.alba.engine.database.UploadAcceptanceEntity
import me.lvca.alba.engine.database.UploadAttemptEntity
import me.lvca.alba.engine.database.UploadSuppressionEntity
import me.lvca.alba.engine.database.VerificationProofEntity
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.ICloudPhotoProtocol
import me.lvca.alba.engine.model.RemotePairCandidate
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.UploadAcceptance
import me.lvca.alba.engine.model.UploadProcessingStatus
import me.lvca.alba.engine.source.ExactOriginalSource
import java.util.UUID

/**
 * Looks for an uncertain upload in iCloud by exact bytes. One exact match verifies it; several, or none by the
 * deadline, end it unresolved and suppress the bytes so Alba never uploads them again.
 *
 * One case is not uncertain: an attempt Apple never accepted, whose write journal proves it never sent the request
 * that registers a photo, and for which a complete read of the library's changes since it started shows no exact copy.
 * iCloud did not receive it, so the transfer goes back to READY for a fresh attempt after a backoff, up to
 * [MAX_AUTOMATIC_RETRIES] times; after that it waits for the user's Retry. Nothing is suppressed, since nothing of it is
 * in iCloud.
 */
internal class Reconciler(
    private val database: AlbaDatabase,
    private val originals: ExactOriginalSource,
    private val protocol: ICloudPhotoProtocol,
    private val authority: AutomaticBackupAuthority,
    private val stages: UploadStages,
    private val now: () -> Long,
) {
    private val transferDao = database.transferDao()
    private val verificationDao = database.verificationDao()

    /** Advances one due reconciliation. Returns 1 when it recorded progress. */
    suspend fun reconcileOne(binding: AccountBinding): Int {
        val transfer = transferDao.findNextReconciliation(binding.accountId, now(), authority.mayUseNetwork()) ?: return 0
        val attemptId = requireNotNull(transfer.activeAttemptId)
        val attempt = requireNotNull(transferDao.findAttempt(attemptId))
        val source = requireNotNull(transferDao.findStagedSource(attempt.stagedSourceId))
        val acceptance = transferDao.findAcceptance(attemptId)?.toModel()
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
                transferDao.markReadFailure(transfer.transferId, attemptId,
                    now() + RECONCILIATION_READ_RETRY_MILLIS, now(),
                    StoredMessage.UPLOAD_PROCESSING.code)
                return 1
            }
            protocol.findUploadCandidates(binding, cursor, acceptance, RECONCILIATION_PAGE_LIMIT)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            transferDao.markReadFailure(
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
                transferDao.markReadFailure(
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
        val allMatches = verificationDao.findReconciliationMatches(attemptId)

        when {
            page.moreComing && page.nextChangeToken != null -> transferDao.advanceReconciliationToken(
                transfer.transferId,
                attemptId,
                page.nextChangeToken,
                now(),
                now(),
            )
            page.moreComing -> transferDao.markReadFailure(
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
            allMatches.isEmpty() && acceptance == null && protocol.registrationNeverSent(binding, attemptId) ->
                retryProvenNotReceived(binding, transfer, attempt)
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
            else -> transferDao.markReadFailure(
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
                val entity = pair.toPairEntity(binding, observedAt)
                verificationDao.insertRemotePair(entity)
                verificationDao.insertReconciliationMatch(
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
        val pairId = remotePairId(binding, pair)
        val linkId = UUID.randomUUID().toString()
        val proofId = UUID.randomUUID().toString()
        database.withTransaction {
            val fresh = requireNotNull(transferDao.findTransfer(transfer.transferId))
            require(fresh.state in RECONCILABLE_STATES && fresh.activeAttemptId == attempt.attemptId)
            verificationDao.insertRemotePair(pair.toPairEntity(binding, verifiedAt))
            verificationDao.insertAssetLink(
                AssetLinkEntity(
                    linkId, binding.accountId, transfer.mediaId, source.stagedSourceId,
                    pairId, attempt.attemptId, verifiedAt, "CURRENT",
                ),
            )
            verificationDao.insertVerificationProof(
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
            check(transferDao.markVerified(transfer.transferId, attempt.attemptId, verifiedAt) == 1)
            check(transferDao.releaseWriteSlot(binding.accountId, attempt.attemptId) == 1)
        }
        stages.release(source)
        carryPhoneMetadata(binding, attempt, source, pair.asset.name)
    }

    /**
     * Marks the verified asset a favourite and gives it its caption when the phone has them. It runs once, when the
     * upload is verified, and only on an asset this upload created: Apple accepted it as new, and the verified asset is
     * the one it named. An asset iCloud already had (a 409 duplicate), or one found without Apple's acceptance, is left
     * as the user keeps it there. The upload is already complete and recorded: a failure here is dropped, never retried
     * as an upload and never shown as a failed backup.
     */
    private suspend fun carryPhoneMetadata(
        binding: AccountBinding,
        attempt: UploadAttemptEntity,
        source: StagedSourceEntity,
        assetRecordName: String,
    ) {
        runSuspendCatching {
            val acceptance = transferDao.findAcceptance(attempt.attemptId) ?: return
            if (acceptance.duplicateHint != false || acceptance.assetRecordName != assetRecordName) return
            val metadata = originals.phoneMetadata(source.contentUri, source.mimeType)
            if (!metadata.isEmpty) protocol.applyPhoneMetadata(binding, assetRecordName, metadata)
        }
    }

    /** iCloud did not receive [attempt]: start afresh after a backoff, or, with retries used up, leave it to the user. */
    private suspend fun retryProvenNotReceived(binding: AccountBinding, transfer: TransferEntity, attempt: UploadAttemptEntity) {
        val retries = transfer.automaticRetries
        database.withTransaction {
            val changed = if (retries < MAX_AUTOMATIC_RETRIES) {
                transferDao.retryAfterProvenNotReceived(transfer.transferId, attempt.attemptId, now() + retryBackoffMillis(retries), now())
            } else {
                transferDao.stopRetryingProvenNotReceived(transfer.transferId, attempt.attemptId, now())
            }
            check(changed == 1)
            check(transferDao.releaseWriteSlot(binding.accountId, attempt.attemptId) == 1)
        }
    }

    private suspend fun markUnresolved(
        transfer: TransferEntity,
        attempt: UploadAttemptEntity,
        source: StagedSourceEntity,
        state: TransferState,
        message: String,
    ) {
        database.withTransaction {
            check(transferDao.markUnresolvedTerminal(transfer.transferId, attempt.attemptId, state, now(), message) == 1)
            transferDao.insertSuppression(
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

    private fun UploadAcceptanceEntity.toModel() = UploadAcceptance(
        requestUuid, masterRecordName, assetRecordName, duplicateHint, uploadJobId,
    )

    companion object {
        private const val RECONCILIATION_READ_RETRY_MILLIS = 15 * 1_000L
        private const val RECONCILIATION_PAGE_LIMIT = 200

        /** Fresh attempts after ones proven not received, before the user is asked. */
        internal const val MAX_AUTOMATIC_RETRIES = 3

        private val RETRY_BACKOFF_MILLIS = longArrayOf(60_000L, 5 * 60_000L, 15 * 60_000L)

        /** How long to wait before the fresh attempt that follows [retries] earlier ones. */
        internal fun retryBackoffMillis(retries: Int): Long = RETRY_BACKOFF_MILLIS[retries.coerceIn(0, RETRY_BACKOFF_MILLIS.lastIndex)]
    }
}
