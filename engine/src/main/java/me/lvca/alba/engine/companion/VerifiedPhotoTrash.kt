package me.lvca.alba.engine.companion

import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.ReclaimProposalEntity
import me.lvca.alba.engine.database.ReclaimProposalItemEntity
import me.lvca.alba.engine.database.TrashAttemptEntity
import me.lvca.alba.engine.database.TrashObligationEntity
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

/** Exact-proof checks and Android consent obligations share the companion's account gate. */
internal class VerifiedPhotoTrash(
    private val database: AlbaDatabase,
    private val originals: ExactOriginalSource,
    private val protocol: ICloudPhotoProtocol,
    private val scheduler: WakeupScheduler,
    private val now: () -> Long,
    private val accounts: AccountBindingSync,
) {
    private val accountDao = database.accountDao()
    private val transferDao = database.transferDao()
    private val trashDao = database.trashDao()
    private val verificationDao = database.verificationDao()

    suspend fun prepareTrash(items: Set<String>): ReclaimProposal {
        refuseUnless(items.isNotEmpty(), StoredMessage.SELECT_AT_LEAST_ONE_VERIFIED_PHOTO)
        val binding = accounts.requireActive()
        val proposalId = UUID.randomUUID().toString()
        val checked = mutableListOf<ReclaimProposalItemEntity>()
        val checkedAt = now()

        for (mediaId in items) {
            val proof = verificationDao.findCurrentProof(mediaId, binding.accountId)
                ?: return blockedProposal(proposalId, StoredMessage.THIS_PHOTO_DOES_NOT_HAVE_A_CURRENT_EXACT_ICLOUD_PROOF.code)
            val source = transferDao.findStagedSource(proof.stagedSourceId)
                ?: return blockedProposal(proposalId, StoredMessage.THE_LOCAL_PROOF_SOURCE_IS_UNAVAILABLE.code)
            val pairEntity = verificationDao.findRemotePair(proof.remotePairId)
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
            val active = requireNotNull(accountDao.findActiveAccount())
            refuseUnless(active.toBinding() == binding, StoredMessage.THE_ACCOUNT_CHANGED_DURING_THE_FINAL_ICLOUD_CHECK)
            trashDao.insertReclaimProposal(
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
            trashDao.insertReclaimProposalItems(checked)
        }
        return ReclaimProposal(ReclaimProposalId(proposalId), items, expiresAt)
    }

    suspend fun beginTrash(proposal: ReclaimProposalId): TrashHandoff {
        val row = refuseIfNull(trashDao.findReclaimProposal(proposal.value), StoredMessage.TRASH_PROPOSAL_EXPIRED)
        refuseUnless(row.state == "READY" && row.expiresAtEpochMillis > now(), StoredMessage.TRASH_PROPOSAL_EXPIRED)
        val items = trashDao.findReclaimProposalItems(proposal.value)
        refuseUnless(items.isNotEmpty(), StoredMessage.TRASH_PROPOSAL_HAS_NO_PHOTOS)
        val binding = accounts.requireActive()
        refuseUnless(row.accountId == binding.accountId && row.authEpoch == binding.authEpoch &&
            row.operationFence == binding.operationFence,
            StoredMessage.THE_ICLOUD_ACCOUNT_CHANGED_BEFORE_ANDROID_CONSENT,
        )
        val intentSender = originals.createTrashRequest(items.map(ReclaimProposalItemEntity::contentUri))

        return accounts.gate(binding.accountId).withLock {
            val attemptId = UUID.randomUUID().toString()
            val handedOffAt = now()
            database.withTransaction {
                val active = requireNotNull(accountDao.findActiveAccount())
                refuseUnless(active.toBinding() == binding, StoredMessage.THE_ACCOUNT_CHANGED_BEFORE_ANDROID_CONSENT)
                refuseUnless(
                    trashDao.markReclaimProposalHandedOff(proposal.value, handedOffAt) == 1,
                    StoredMessage.THIS_TRASH_REQUEST_WAS_ALREADY_USED,
                )
                trashDao.insertTrashAttempt(
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
                trashDao.insertTrashObligations(items.map { item ->
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
            trashDao.recordTrashCallback(attempt.value, disposition, result.name, now()) == 1,
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
        trashDao.findApprovedTrashAttempts().forEach { inspectTrashAttempt(it.trashAttemptId) }
    }

    private suspend fun inspectTrashAttempt(attemptId: String) {
        val attempt = trashDao.findTrashAttempt(attemptId) ?: return
        val approved = attempt.consentDisposition == "CALLBACK_OBSERVED_APPROVED"
        val currentBoot = originals.bootSessionId()
        val rebooted = attempt.bootSessionId != null && currentBoot != null && attempt.bootSessionId != currentBoot
        val obligations = trashDao.findTrashObligations(attemptId)
        var allResolved = obligations.isNotEmpty()
        obligations.forEach { obligation ->
            val state = runSuspendCatching { originals.readTrashState(obligation.contentUri) }.getOrNull()
            val resolved = state != null && (rebooted || (approved && state.isTrashed))
            allResolved = allResolved && resolved
            trashDao.updateTrashObligation(
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
        if (allResolved) trashDao.setTrashDisposition(attemptId, if (rebooted) "TERMINAL_RECOVERED_AFTER_REBOOT" else "TERMINAL_EXECUTED")
    }

    private suspend fun resolveDeniedTrashAttempt(attemptId: String) {
        trashDao.findTrashObligations(attemptId).forEach { obligation ->
            trashDao.updateTrashObligation(
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
