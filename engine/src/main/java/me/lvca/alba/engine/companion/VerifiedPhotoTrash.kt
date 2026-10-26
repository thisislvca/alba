package me.lvca.alba.engine.companion

import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.ReclaimProposalEntity
import me.lvca.alba.engine.database.ReclaimProposalItemEntity
import me.lvca.alba.engine.database.TrashAttemptEntity
import me.lvca.alba.engine.database.TrashObligationEntity
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.FreeUpSpaceCandidates
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

    suspend fun prepareTrash(
        items: Set<String>,
        allowPartial: Boolean = false,
        onProgress: suspend (checked: Int, total: Int) -> Unit = { _, _ -> },
    ): ReclaimProposal {
        refuseUnless(items.isNotEmpty(), StoredMessage.SELECT_AT_LEAST_ONE_VERIFIED_PHOTO)
        val binding = accounts.requireActive()
        val proposalId = UUID.randomUUID().toString()
        val checked = mutableListOf<ReclaimProposalItemEntity>()
        val refusals = linkedMapOf<String, String>()
        val ordered = items.toList()
        // The consent window runs from the first check: no item's check is older than it when Android asks.
        val firstCheckAt = now()
        var reached = 0

        while (reached < ordered.size) {
            // A partial batch stops early enough that Android can still ask inside the window; the rest waits.
            val chunkFull = now() - firstCheckAt >= CHECK_BUDGET_MILLIS || checked.size >= MAX_CHUNK
            if (allowPartial && reached > 0 && chunkFull) break
            val mediaId = ordered[reached]
            onProgress(reached, ordered.size)
            val refusal = if (!allowPartial) checkExact(binding, proposalId, mediaId) { checked += it } else {
                // One unreadable item stays on the phone; the rest of the batch is still checked.
                runSuspendCatching { checkExact(binding, proposalId, mediaId) { checked += it } }.getOrElse(::refusalFor)
            }
            reached += 1
            if (refusal != null) {
                if (!allowPartial) return blockedProposal(proposalId, refusal)
                refusals[mediaId] = refusal
            }
        }
        onProgress(reached, ordered.size)
        val unchecked = ordered.drop(reached)
        if (checked.isEmpty()) {
            return blockedProposal(proposalId, refusals.values.firstOrNull() ?: StoredMessage.SELECT_AT_LEAST_ONE_VERIFIED_PHOTO.code)
                .copy(refusals = refusals, unchecked = unchecked)
        }

        val checkedAt = now()
        val expiresAt = firstCheckAt + RECLAIM_PROPOSAL_LIFETIME_MILLIS
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
        return ReclaimProposal(ReclaimProposalId(proposalId), checked.map { it.mediaId }.toSet(), expiresAt,
            refusals = refusals, unchecked = unchecked)
    }

    /**
     * Why a check failed when it threw. Phone reads fail with Alba's own reasons; anything else is an iCloud read that
     * did not complete, which says nothing about whether the copies match.
     */
    private fun refusalFor(error: Throwable): String =
        StoredMessage.decode(error.message)?.code ?: StoredMessage.COULD_NOT_REACH_ICLOUD_TO_CHECK.code

    /**
     * Re-reads [mediaId]'s phone and iCloud originals and compares both with its proof. Hands the checked item to
     * [accept] and returns null when they match exactly; otherwise returns why not.
     */
    private suspend fun checkExact(
        binding: AccountBinding,
        proposalId: String,
        mediaId: String,
        accept: (ReclaimProposalItemEntity) -> Unit,
    ): String? {
        val proof = verificationDao.findCurrentProof(mediaId, binding.accountId)
            ?: return StoredMessage.THIS_PHOTO_DOES_NOT_HAVE_A_CURRENT_EXACT_ICLOUD_PROOF.code
        val source = transferDao.findStagedSource(proof.stagedSourceId)
            ?: return StoredMessage.THE_LOCAL_PROOF_SOURCE_IS_UNAVAILABLE.code
        val pairEntity = verificationDao.findRemotePair(proof.remotePairId)
            ?: return StoredMessage.THE_ICLOUD_RECORD_PROOF_IS_UNAVAILABLE.code
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
        if (!exact) return StoredMessage.THE_CURRENT_LOCAL_AND_ICLOUD_ORIGINALS_NO_LONGER_MATCH.code
        accept(
            ReclaimProposalItemEntity(
                proposalId = proposalId,
                mediaId = mediaId,
                contentUri = source.contentUri,
                proofId = proof.proofId,
                localSha256Hex = local.sha256Hex,
                remoteSha256Hex = remoteHash.sha256Hex(),
                verifiedAtEpochMillis = now(),
            ),
        )
        return null
    }

    /** Phone items with a current proof, oldest first, and the bytes they hold on the phone. */
    suspend fun freeUpSpaceCandidates(): FreeUpSpaceCandidates {
        val rows = verificationDao.findReclaimableOnPhone()
        return FreeUpSpaceCandidates(rows.map { it.mediaId }, rows.sumOf { it.byteCount }, rows.map { it.byteCount })
    }

    suspend fun beginTrash(proposal: ReclaimProposalId): TrashHandoff {
        val row = refuseIfNull(trashDao.findReclaimProposal(proposal.value), StoredMessage.TRASH_PROPOSAL_EXPIRED)
        refuseUnless(row.state == "READY" && row.expiresAtEpochMillis > now(), StoredMessage.TRASH_PROPOSAL_EXPIRED)
        // A phone file edited since its check is left out: only the exact bytes that were verified may be trashed.
        val items = trashDao.findReclaimProposalItems(proposal.value).filter { unchangedSinceCheck(it) }
        refuseUnless(items.isNotEmpty(), StoredMessage.THE_PHONE_FILE_CHANGED_AFTER_ITS_CHECK)
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
            TrashHandoff(TrashAttemptId(attemptId), intentSender, items.map { it.mediaId }.toSet())
        }
    }

    /** Whether [item]'s phone file still has the generation and size its proof verified. */
    private suspend fun unchangedSinceCheck(item: ReclaimProposalItemEntity): Boolean {
        val proof = verificationDao.findProof(item.proofId) ?: return false
        val current = runSuspendCatching { originals.currentRevision(item.contentUri) }.getOrElse { return false }
        return current == null || current == proof.sourceRevision
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

        /** A partial batch stops checking after this long, so Android asks well inside the consent window. */
        const val CHECK_BUDGET_MILLIS = 60 * 1_000L

        /** The most items one Android trash request takes. */
        const val MAX_CHUNK = 2_000
    }
}
