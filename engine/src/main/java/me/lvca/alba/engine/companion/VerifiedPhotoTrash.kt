package me.lvca.alba.engine.companion

import androidx.room.withTransaction
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.ReclaimProposalEntity
import me.lvca.alba.engine.database.ReclaimProposalItemEntity
import me.lvca.alba.engine.database.TrashAttemptEntity
import me.lvca.alba.engine.database.TrashObligationEntity
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.ICloudPhotoProtocol
import me.lvca.alba.engine.model.ReclaimProposal
import me.lvca.alba.engine.model.ReclaimProposalId
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.SystemConsentCallback
import me.lvca.alba.engine.model.TrashAttemptId
import me.lvca.alba.engine.model.TrashHandoff
import me.lvca.alba.engine.model.WakeupScheduler
import me.lvca.alba.engine.source.ExactOriginalSource
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Exact-proof checks and Android consent obligations share the companion's account gate. */
internal class VerifiedPhotoTrash(
    private val database: AlbaDatabase,
    private val originals: ExactOriginalSource,
    private val protocol: ICloudPhotoProtocol,
    private val scheduler: WakeupScheduler,
    private val now: () -> Long,
    private val requireActiveBinding: suspend () -> AccountBinding,
    private val accountGate: (String) -> Mutex,
) {
    private val dao = database.photoWriteDao()

    suspend fun prepareTrash(items: Set<String>): ReclaimProposal {
        refuseUnless(items.isNotEmpty(), StoredMessage.SELECT_AT_LEAST_ONE_VERIFIED_PHOTO)
        val binding = requireActiveBinding()
        val proposalId = UUID.randomUUID().toString()
        val checked = mutableListOf<ReclaimProposalItemEntity>()
        val checkedAt = now()

        for (mediaId in items) {
            val proof = dao.findCurrentProof(mediaId, binding.accountId)
                ?: return blockedProposal(proposalId, StoredMessage.THIS_PHOTO_DOES_NOT_HAVE_A_CURRENT_EXACT_ICLOUD_PROOF.code)
            val source = dao.findStagedSource(proof.stagedSourceId)
                ?: return blockedProposal(proposalId, StoredMessage.THE_LOCAL_PROOF_SOURCE_IS_UNAVAILABLE.code)
            val pairEntity = dao.findRemotePair(proof.remotePairId)
                ?: return blockedProposal(proposalId, StoredMessage.THE_ICLOUD_RECORD_PROOF_IS_UNAVAILABLE.code)
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
                return blockedProposal(proposalId, StoredMessage.THE_CURRENT_LOCAL_AND_ICLOUD_ORIGINALS_NO_LONGER_MATCH.code)
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
            refuseUnless(active.toBinding() == binding, StoredMessage.THE_ACCOUNT_CHANGED_DURING_THE_FINAL_ICLOUD_CHECK)
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

    suspend fun beginTrash(proposal: ReclaimProposalId): TrashHandoff {
        val row = refuseIfNull(dao.findReclaimProposal(proposal.value), StoredMessage.TRASH_PROPOSAL_EXPIRED)
        refuseUnless(row.state == "READY" && row.expiresAtEpochMillis > now(), StoredMessage.TRASH_PROPOSAL_EXPIRED)
        val items = dao.findReclaimProposalItems(proposal.value)
        refuseUnless(items.isNotEmpty(), StoredMessage.TRASH_PROPOSAL_HAS_NO_PHOTOS)
        val binding = requireActiveBinding()
        refuseUnless(row.accountId == binding.accountId && row.authEpoch == binding.authEpoch &&
            row.operationFence == binding.operationFence,
            StoredMessage.THE_ICLOUD_ACCOUNT_CHANGED_BEFORE_ANDROID_CONSENT,
        )
        val intentSender = originals.createTrashRequest(items.map(ReclaimProposalItemEntity::contentUri))

        return accountGate(binding.accountId).withLock {
            val attemptId = UUID.randomUUID().toString()
            val handedOffAt = now()
            database.withTransaction {
                val active = requireNotNull(dao.findActiveAccount())
                refuseUnless(active.toBinding() == binding, StoredMessage.THE_ACCOUNT_CHANGED_BEFORE_ANDROID_CONSENT)
                refuseUnless(
                    dao.markReclaimProposalHandedOff(proposal.value, handedOffAt) == 1,
                    StoredMessage.THIS_TRASH_REQUEST_WAS_ALREADY_USED,
                )
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

    suspend fun recordTrashResult(attempt: TrashAttemptId, result: SystemConsentCallback) {
        val disposition = if (result == SystemConsentCallback.APPROVED) {
            "CALLBACK_OBSERVED_APPROVED"
        } else {
            "TERMINAL_DENIED"
        }
        refuseUnless(
            dao.recordTrashCallback(attempt.value, disposition, result.name, now()) == 1,
            StoredMessage.THIS_ANDROID_TRASH_RESULT_IS_NO_LONGER_ACTIVE,
        )
        if (result == SystemConsentCallback.APPROVED) {
            inspectTrashAttempt(attempt.value)
            runSuspendCatching { scheduler.scheduleSoon() }
        } else {
            resolveDeniedTrashAttempt(attempt.value)
        }
    }

    suspend fun inspectApprovedTrashAttempts() {
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
            val state = runSuspendCatching { originals.readTrashState(obligation.contentUri) }.getOrNull()
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

    private companion object {
        const val RECLAIM_PROPOSAL_LIFETIME_MILLIS = 2 * 60 * 1_000L
    }
}
