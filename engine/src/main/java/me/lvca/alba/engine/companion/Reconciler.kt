package me.lvca.alba.engine.companion

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.ReconciliationMatchEntity
import me.lvca.alba.engine.database.StagedSourceEntity
import me.lvca.alba.engine.database.TransferEntity
import me.lvca.alba.engine.database.TransferState
import me.lvca.alba.engine.database.UploadAttemptEntity
import me.lvca.alba.engine.database.UploadSuppressionEntity
import me.lvca.alba.engine.database.VerificationProofEntity
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.BackupAttribute
import me.lvca.alba.engine.model.BackupBreadcrumb
import me.lvca.alba.engine.model.BackupDiagnostics
import me.lvca.alba.engine.model.BackupEvent
import me.lvca.alba.engine.model.BackupPhase
import me.lvca.alba.engine.model.BackupTelemetry
import me.lvca.alba.engine.model.BackupTelemetry.traced
import me.lvca.alba.engine.model.DiagnosticSeverity
import me.lvca.alba.engine.model.ICloudPhotoProtocol
import me.lvca.alba.engine.model.RemotePairCandidate
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.UploadAcceptance
import me.lvca.alba.engine.model.UploadProcessingStatus
import me.lvca.alba.engine.model.UploadReceipt
import me.lvca.alba.engine.source.ExactOriginalSource
import java.util.UUID

/**
 * Looks for an upload in iCloud's change feed. With Apple's receipt for the sent bytes (the normal case), a pair matches
 * when its CPLMaster's original has the receipt's size and checksum and its fingerprint is that checksum: nothing is
 * downloaded, and the proof records the method RECEIPT. An attempt with no receipt (one from before receipts were kept)
 * falls back to the [ByteCheck], downloading each candidate. One match verifies the upload; several, or none by the
 * deadline, end it uncertain: the bytes are suppressed so it is never sent twice, and [UncertainHealer] looks for it
 * again after later library refreshes.
 *
 * One case is not uncertain: an attempt Apple never accepted, whose write journal proves it never sent the request
 * that registers a photo, and for which a complete read of the library's changes since it started shows no exact copy.
 * iCloud did not receive it, so the transfer goes back to READY for a fresh attempt, with a fresh upload URL, after a
 * backoff ([retryBackoffMillis]) that grows to a daily cap; it keeps trying until it goes through or fails for good.
 * Nothing is suppressed, since nothing of it is in iCloud.
 */
internal class Reconciler(
    private val database: AlbaDatabase,
    private val originals: ExactOriginalSource,
    private val protocol: ICloudPhotoProtocol,
    private val authority: AutomaticBackupAuthority,
    private val stages: UploadStages,
    private val now: () -> Long,
    private val byteCheck: ByteCheck = ByteCheck(protocol),
    private val diagnostics: BackupDiagnostics = BackupDiagnostics.None,
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
                // iCloud took the bytes but would not keep the file: nothing retries it, and it is never sent again.
                markUnresolved(transfer, attempt, source, TransferState.NOT_OBSERVED,
                    StoredMessage.UPLOAD_PROCESSING_FAILED.code, FailureReasons.ICLOUD_REJECTED)
                return 1
            }
            if (processing is UploadProcessingStatus.Processing &&
                now() < (transfer.reconciliationDeadlineEpochMillis ?: Long.MAX_VALUE)) {
                transferDao.markReadFailure(transfer.transferId, attemptId,
                    now() + RECONCILIATION_READ_RETRY_MILLIS, now(),
                    StoredMessage.UPLOAD_PROCESSING.code)
                return 1
            }
            diagnostics.traced(BackupPhase.RECEIPT_MATCH, mapOf(BackupAttribute.SIZE_BUCKET to BackupTelemetry.sizeBucket(source.byteCount))) {
                protocol.findUploadCandidates(binding, cursor, acceptance, RECONCILIATION_PAGE_LIMIT)
            }
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

        // Apple's receipt for the sent bytes: from the acceptance, or from the protocol's journal when the registration
        // that followed it was lost.
        val receipt = acceptance?.receipt ?: try {
            protocol.uploadReceipt(binding, attemptId)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            null
        }
        val current = page.candidates.distinctBy { "${it.master.name}:${it.asset.name}" }
            .filter { it.relationIsCurrent && !it.masterDeleted && !it.assetDeleted }
        val exactMatches = mutableListOf<RemotePairCandidate>()
        if (receipt != null) exactMatches += current.filter { it.matchesReceipt(receipt, source.byteCount) }
        if (receipt != null) reportReceiptMismatch(receipt, acceptance, current, source)
        // Without a receipt, each candidate is downloaded and compared byte for byte.
        for (pair in current.takeIf { receipt == null }.orEmpty()) {
            val checked = try {
                byteCheck.compare(binding, pair, source.byteCount, source.sha256Hex)
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
            if (checked.matches) exactMatches += pair
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
                if (receipt != null) VerificationProofEntity.METHOD_RECEIPT else VerificationProofEntity.METHOD_BYTES,
            )
            allMatches.isEmpty() && acceptance == null && protocol.registrationNeverSent(binding, attemptId) ->
                retryProvenNotReceived(binding, transfer, attempt)
            allMatches.size > 1 -> markUnresolved(
                transfer,
                attempt,
                source,
                TransferState.AMBIGUOUS,
                StoredMessage.SEVERAL_ICLOUD_COPIES_MATCH_CHECKING_AGAIN.code,
            )
            now() >= (transfer.reconciliationDeadlineEpochMillis ?: Long.MAX_VALUE) -> markUnresolved(
                transfer,
                attempt,
                source,
                TransferState.NOT_OBSERVED,
                StoredMessage.NOT_FOUND_IN_ICLOUD_YET_CHECKING_AGAIN.code,
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

    /** The item Apple named on acceptance is there but does not carry the receipt: worth knowing about, never a match. */
    private fun reportReceiptMismatch(
        receipt: UploadReceipt,
        acceptance: UploadAcceptance?,
        current: List<RemotePairCandidate>,
        source: StagedSourceEntity,
    ) {
        if (current.none { it.master.name == acceptance?.masterRecordName && !it.matchesReceipt(receipt, source.byteCount) }) return
        diagnostics.capture(
            BackupEvent.RECEIPT_MISMATCH, DiagnosticSeverity.ERROR,
            mapOf(
                BackupAttribute.MEDIA_KIND to BackupTelemetry.mediaKind(source.mimeType),
                BackupAttribute.SIZE_BUCKET to BackupTelemetry.sizeBucket(source.byteCount),
            ),
        )
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
        method: String,
    ) {
        val verifiedAt = now()
        database.withTransaction {
            val fresh = requireNotNull(transferDao.findTransfer(transfer.transferId))
            require(fresh.state in RECONCILABLE_STATES && fresh.activeAttemptId == attempt.attemptId)
            verificationDao.recordVerifiedLink(
                binding, transfer.mediaId, source, attempt, pair, method, verifiedAt,
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

    /** iCloud did not receive [attempt]: start afresh after a backoff that grows with each try, up to a day. */
    private suspend fun retryProvenNotReceived(binding: AccountBinding, transfer: TransferEntity, attempt: UploadAttemptEntity) {
        val retries = transfer.automaticRetries
        diagnostics.breadcrumb(BackupBreadcrumb.RETRY_SCHEDULED, mapOf(BackupAttribute.COUNT to retries + 1,
            BackupAttribute.DURATION_MS to retryBackoffMillis(retries)))
        database.withTransaction {
            val changed = transferDao.retryAfterProvenNotReceived(
                transfer.transferId, attempt.attemptId, now() + retryBackoffMillis(retries), now(),
            )
            check(changed == 1)
            check(transferDao.releaseWriteSlot(binding.accountId, attempt.attemptId) == 1)
        }
    }

    /**
     * Ends [attempt] uncertain. Its bytes are suppressed, so it is never sent again while uncertain, and its write slot is
     * given back so other uploads go on; [UncertainHealer] looks for it again after later library refreshes.
     */
    private suspend fun markUnresolved(
        transfer: TransferEntity,
        attempt: UploadAttemptEntity,
        source: StagedSourceEntity,
        state: TransferState,
        message: String,
        failureReason: String? = null,
    ) {
        diagnostics.capture(BackupEvent.UPLOAD_UNRESOLVED, DiagnosticSeverity.WARNING, mapOf(
            BackupAttribute.OUTCOME to state.name,
            BackupAttribute.MEDIA_KIND to BackupTelemetry.mediaKind(source.mimeType),
            BackupAttribute.SIZE_BUCKET to BackupTelemetry.sizeBucket(source.byteCount),
        ) + (failureReason?.let { mapOf(BackupAttribute.ERROR to it) } ?: emptyMap()))
        database.withTransaction {
            check(transferDao.markUnresolvedTerminal(transfer.transferId, attempt.attemptId, state, now(), message, failureReason) == 1)
            transferDao.releaseWriteSlot(transfer.accountId, attempt.attemptId)
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

    companion object {
        private const val RECONCILIATION_READ_RETRY_MILLIS = 15 * 1_000L
        private const val RECONCILIATION_PAGE_LIMIT = 200

        /** 1, 5, 15 and 60 minutes, then [RETRY_BACKOFF_CAP_MILLIS]. */
        private val RETRY_BACKOFF_MILLIS = longArrayOf(60_000L, 5 * 60_000L, 15 * 60_000L, 60 * 60_000L)

        /** The longest wait between fresh attempts: once a day, for as long as it takes. */
        internal const val RETRY_BACKOFF_CAP_MILLIS = 24 * 60 * 60_000L

        /** How long to wait before the fresh attempt that follows [retries] earlier ones. */
        internal fun retryBackoffMillis(retries: Int): Long = RETRY_BACKOFF_MILLIS.getOrElse(retries.coerceAtLeast(0)) { RETRY_BACKOFF_CAP_MILLIS }
    }
}
