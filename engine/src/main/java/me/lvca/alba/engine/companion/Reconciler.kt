package me.lvca.alba.engine.companion

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.AssetLinkEntity
import me.lvca.alba.engine.database.ReconciliationMatchEntity
import me.lvca.alba.engine.database.RemoteAssetPairEntity
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
import java.security.MessageDigest
import java.util.UUID

/**
 * Looks for an uncertain upload in iCloud by exact bytes. One exact match verifies it; several, or none by the
 * deadline, end it unresolved and suppress the bytes so Alba never uploads them again.
 */
internal class Reconciler(
    private val database: AlbaDatabase,
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
                val entity = pair.toEntity(binding, observedAt)
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
        val pairId = stableId(binding.accountId, pair.master.name, pair.asset.name)
        val linkId = UUID.randomUUID().toString()
        val proofId = UUID.randomUUID().toString()
        database.withTransaction {
            val fresh = requireNotNull(transferDao.findTransfer(transfer.transferId))
            require(fresh.state in RECONCILABLE_STATES && fresh.activeAttemptId == attempt.attemptId)
            verificationDao.insertRemotePair(pair.toEntity(binding, verifiedAt))
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

    private fun stableId(vararg values: String): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val RECONCILIATION_READ_RETRY_MILLIS = 15 * 1_000L
        const val RECONCILIATION_PAGE_LIMIT = 200
    }
}
