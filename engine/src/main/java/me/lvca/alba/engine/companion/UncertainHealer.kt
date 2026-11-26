package me.lvca.alba.engine.companion

import androidx.room.withTransaction
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.StagedSourceEntity
import me.lvca.alba.engine.database.TransferEntity
import me.lvca.alba.engine.database.TransferState
import me.lvca.alba.engine.database.UploadAttemptEntity
import me.lvca.alba.engine.database.VerificationProofEntity
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.CloudLibraryIndex
import me.lvca.alba.engine.model.ICloudPhotoProtocol
import me.lvca.alba.engine.model.LibraryCandidate
import me.lvca.alba.engine.model.RemotePairCandidate
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.UploadReceipt

/**
 * When an uncertain upload is looked for again: after each library refresh for [EACH_REFRESH_MILLIS] from when it became
 * uncertain, then at most daily, each time only after a refresh newer than the last look. After [WINDOW_MILLIS] the next
 * look is the last one that may change it: an upload never observed is sent again only when nothing in iCloud could be it.
 */
internal object HealSchedule {
    const val EACH_REFRESH_MILLIS = 24 * 60 * 60 * 1_000L
    const val DAILY_MILLIS = 24 * 60 * 60 * 1_000L

    /** After each refresh for a day, then daily for seven more. */
    const val WINDOW_MILLIS = EACH_REFRESH_MILLIS + 7 * DAILY_MILLIS

    /**
     * Whether an upload uncertain since [since], last looked for at [checkedAt] (null: never since), should be looked for
     * at [now] in a library last refreshed at [refreshedAt] (null: never read).
     */
    fun isDue(since: Long, checkedAt: Long?, refreshedAt: Long?, now: Long): Boolean {
        val refreshed = refreshedAt ?: return false
        val last = checkedAt ?: since
        if (refreshed <= last) return false
        return now - since < EACH_REFRESH_MILLIS || checkedAt == null || now - checkedAt >= DAILY_MILLIS
    }

    /** Whether the healing window of an upload uncertain since [since] is over at [now]. */
    fun windowOver(since: Long, now: Long): Boolean = now - since >= WINDOW_MILLIS
}

/**
 * Heals uploads that ended uncertain (AMBIGUOUS or NOT_OBSERVED). A NOT_OBSERVED upload is often one the change feed
 * reported late, so after later library refreshes ([HealSchedule]) Alba looks for it in the refreshed catalog: same-size
 * items are read afresh and matched against Apple's receipt for the sent bytes, as reconciliation does. One match verifies
 * it and lifts the suppression of its bytes.
 *
 * Once the window is over, a NOT_OBSERVED upload with nothing that could be it is sent again: no receipt match, and no
 * catalog candidate by size and name or capture time ([ExistingCopyMatcher]). Any candidate settles it the other way: a
 * single exact copy, compared byte for byte, verifies it; anything else makes it AMBIGUOUS, never a second upload.
 *
 * An AMBIGUOUS upload is never sent again. It is resolved with the byte check ([resolveByBytes]) only when the user opens
 * it or before Free up space, since that downloads each candidate; until then it waits without holding up other uploads.
 */
internal class UncertainHealer(
    private val database: AlbaDatabase,
    private val protocol: ICloudPhotoProtocol,
    private val index: CloudLibraryIndex,
    private val stages: UploadStages,
    private val now: () -> Long,
    private val byteCheck: ByteCheck = ByteCheck(protocol),
) {
    private val transferDao = database.transferDao()
    private val verificationDao = database.verificationDao()

    /**
     * Looks again for the uncertain uploads that are due and [mayLook] allows on the current network, a few per pass.
     * Returns how many it settled.
     */
    suspend fun healDue(binding: AccountBinding, mayLook: (TransferEntity) -> Boolean = { true }): Int {
        val refreshedAt = runSuspendCatching { index.refreshedAt(binding) }.getOrNull() ?: return 0
        val due = transferDao.findUnresolved(binding.accountId).filter { row ->
            mayLook(row) && HealSchedule.isDue(row.unresolvedSinceEpochMillis ?: row.updatedAtEpochMillis, row.healCheckedAtEpochMillis, refreshedAt, now())
        }
        return due.take(MAX_LOOKS_PER_PASS).count { heal(binding, it) }
    }

    /** Looks for [row]'s upload in the refreshed library; true when that settled it. */
    private suspend fun heal(binding: AccountBinding, row: TransferEntity): Boolean {
        val found = look(binding, row) ?: return false
        val since = row.unresolvedSinceEpochMillis ?: row.updatedAtEpochMillis
        return when {
            found.receiptMatches.size == 1 -> verify(binding, found, found.receiptMatches.single(), VerificationProofEntity.METHOD_RECEIPT)
            found.receiptMatches.size > 1 -> {
                stillUncertain(found, ambiguous = true)
                false
            }
            row.state == TransferState.NOT_OBSERVED && row.failureReason == null && HealSchedule.windowOver(since, now()) ->
                decideAfterWindow(binding, found)
            else -> {
                stillUncertain(found, ambiguous = false)
                false
            }
        }
    }

    /** What one look in the refreshed library found for an uncertain upload. */
    private class Look(
        val row: TransferEntity,
        val attempt: UploadAttemptEntity,
        val source: StagedSourceEntity,
        val library: List<LibraryCandidate>,
        val receiptMatches: List<RemotePairCandidate>,
    )

    /**
     * Reads [row]'s same-size library items afresh and matches them against its receipt. Null, with the look recorded so it
     * waits for the next refresh, when the library could not be read.
     */
    private suspend fun look(binding: AccountBinding, row: TransferEntity): Look? {
        val attemptId = row.activeAttemptId ?: return null
        val attempt = transferDao.findAttempt(attemptId) ?: return null
        val source = transferDao.findStagedSource(attempt.stagedSourceId) ?: return null
        val read = runSuspendCatching {
            val library = index.candidates(binding, source.byteCount, ExistingCopyMatcher.MAX_SAME_SIZE)
            val receipt = receiptOf(binding, attemptId)
            val matches = if (receipt == null) emptyList() else library.mapNotNull { candidate ->
                protocol.libraryPair(binding, candidate.masterRecordName, candidate.assetRecordName)
                    ?.takeIf { it.isCurrent() && it.matchesReceipt(receipt, source.byteCount) }
            }.distinctBy { it.master.name to it.asset.name }
            library to matches
        }
        val (library, matches) = read.getOrElse {
            transferDao.noteHealChecked(row.transferId, attemptId, now())
            return null
        }
        return Look(row, attempt, source, library, matches)
    }

    private suspend fun receiptOf(binding: AccountBinding, attemptId: String): UploadReceipt? =
        transferDao.findAcceptance(attemptId)?.toModel()?.receipt ?: runSuspendCatching { protocol.uploadReceipt(binding, attemptId) }.getOrNull()

    /**
     * The window is over and the receipt matched nothing. With no candidate at all the upload is sent again; a single
     * exact copy verifies it; any other candidate makes it ambiguous, since a second upload could duplicate it.
     */
    private suspend fun decideAfterWindow(binding: AccountBinding, found: Look): Boolean {
        val source = found.source
        val phone = PhoneOriginal(source.displayName, source.byteCount, source.sha256Hex, source.capturedAtEpochMillis)
        val candidates = when (val selection = ExistingCopyMatcher.select(phone, found.library)) {
            CandidateSelection.None -> return reupload(found)
            is CandidateSelection.TooMany -> emptyList()
            is CandidateSelection.Check -> selection.candidates
        }
        val exact = runSuspendCatching { exactCopies(binding, source, candidates.map { it.masterRecordName to it.assetRecordName }) }
            .getOrElse {
                // A failed read proves nothing either way: look again after the next refresh.
                transferDao.noteHealChecked(found.row.transferId, found.attempt.attemptId, now())
                return false
            }
        if (exact.size == 1) return verify(binding, found, exact.single(), VerificationProofEntity.METHOD_BYTES)
        stillUncertain(found, ambiguous = true)
        return false
    }

    /** No copy of the upload appeared, and nothing in iCloud could be it: its bytes are sent again in a fresh attempt. */
    private suspend fun reupload(found: Look): Boolean = database.withTransaction {
        val attemptId = found.attempt.attemptId
        // Already looked for in the library just now, so the upload does not look again first.
        if (transferDao.reuploadNotObserved(found.row.transferId, attemptId, now(), ExistingCopyCheck.RESULT_NOT_FOUND) != 1) {
            return@withTransaction false
        }
        transferDao.deactivateSuppressions(attemptId)
        true
    }

    private suspend fun stillUncertain(found: Look, ambiguous: Boolean) {
        val row = found.row
        if (ambiguous && row.state == TransferState.NOT_OBSERVED) {
            transferDao.markNotObservedAmbiguous(
                row.transferId, found.attempt.attemptId, now(), StoredMessage.SEVERAL_ICLOUD_COPIES_MATCH_CHECKING_AGAIN.code,
            )
        } else {
            transferDao.noteHealChecked(row.transferId, found.attempt.attemptId, now())
        }
    }

    /** The library items named by [names] whose originals are exactly [source]'s bytes, each read and downloaded afresh. */
    private suspend fun exactCopies(
        binding: AccountBinding,
        source: StagedSourceEntity,
        names: List<Pair<String, String>>,
    ): List<RemotePairCandidate> = names.distinct().mapNotNull { (master, asset) ->
        protocol.libraryPair(binding, master, asset)?.takeIf { pair ->
            pair.isCurrent() && byteCheck.compare(binding, pair, source.byteCount, source.sha256Hex).matches
        }
    }

    /**
     * Resolves an AMBIGUOUS upload with the byte check: each item that matched it, or each catalog candidate, is downloaded
     * afresh and compared with the phone's bytes. Exact copies are the same original, so the upload is in iCloud whichever
     * of them it made: it is verified against the one Apple's acceptance named when that is exact, else the first. Runs
     * only when the user opens the item or before Free up space. Returns true when it verified the upload.
     */
    suspend fun resolveByBytes(binding: AccountBinding, transferId: String): Boolean {
        val row = transferDao.findTransfer(transferId)?.takeIf { it.state == TransferState.AMBIGUOUS && it.accountId == binding.accountId }
            ?: return false
        val attemptId = row.activeAttemptId ?: return false
        val attempt = transferDao.findAttempt(attemptId) ?: return false
        val source = transferDao.findStagedSource(attempt.stagedSourceId) ?: return false
        val matched = verificationDao.findReconciliationMatches(attemptId).map { it.masterRecordName to it.assetRecordName }
        val names = matched.ifEmpty {
            runSuspendCatching { index.candidates(binding, source.byteCount, ExistingCopyMatcher.MAX_SAME_SIZE) }.getOrDefault(emptyList())
                .map { it.masterRecordName to it.assetRecordName }
        }
        val exact = runSuspendCatching { exactCopies(binding, source, names) }.getOrElse { return false }
        if (exact.isEmpty()) return false
        val acceptance = transferDao.findAcceptance(attemptId)
        val chosen = exact.firstOrNull { it.master.name == acceptance?.masterRecordName && it.asset.name == acceptance.assetRecordName }
            ?: exact.minBy { it.master.name }
        val found = Look(row, attempt, source, emptyList(), emptyList())
        return verify(binding, found, chosen, VerificationProofEntity.METHOD_BYTES)
    }

    /** Resolves every AMBIGUOUS upload of the account by bytes, at most [limit]; returns how many it verified. */
    suspend fun resolveAllByBytes(binding: AccountBinding, limit: Int = MAX_BYTE_RESOLUTIONS): Int =
        transferDao.findUnresolved(binding.accountId).filter { it.state == TransferState.AMBIGUOUS }.take(limit)
            .count { resolveByBytes(binding, it.transferId) }

    /** [found]'s upload is [pair] after all: it is verified, and its bytes are no longer suppressed. */
    private suspend fun verify(binding: AccountBinding, found: Look, pair: RemotePairCandidate, method: String): Boolean {
        val verifiedAt = now()
        val healed = database.withTransaction {
            if (transferDao.markHealed(found.row.transferId, found.attempt.attemptId, verifiedAt) != 1) return@withTransaction false
            verificationDao.recordVerifiedLink(
                binding, found.row.mediaId, found.source, found.attempt, pair, method, verifiedAt,
            )
            transferDao.deactivateSuppressions(found.attempt.attemptId)
            // Held only by uploads uncertain from before schema 25; given back once settled either way.
            transferDao.releaseWriteSlot(binding.accountId, found.attempt.attemptId)
            true
        }
        if (healed) stages.release(found.source)
        return healed
    }

    private fun RemotePairCandidate.isCurrent() = relationIsCurrent && !masterDeleted && !assetDeleted

    private companion object {
        /** Uncertain uploads looked for in one pass; each reads its same-size library items afresh. */
        const val MAX_LOOKS_PER_PASS = 5

        /** Ambiguous uploads byte-checked at most before Free up space lists what it can remove. */
        const val MAX_BYTE_RESOLUTIONS = 20
    }
}
