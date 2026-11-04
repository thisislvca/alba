package me.lvca.alba.engine.companion

import me.lvca.alba.engine.database.StagedSourceEntity
import me.lvca.alba.engine.database.TransferEntity
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.CloudLibraryIndex
import me.lvca.alba.engine.model.ICloudPhotoProtocol
import me.lvca.alba.engine.model.LibraryCandidate
import me.lvca.alba.engine.model.LibraryIndexState
import me.lvca.alba.engine.model.RemotePairCandidate
import me.lvca.alba.engine.model.StoredMessage
import java.io.IOException
import java.io.OutputStream

/** What backup does with one staged item after looking for it in the iCloud library. */
internal sealed interface ExistingCopyOutcome {
    /** Exactly one library item holds these exact bytes: link to it, upload nothing. */
    data class Matched(val pair: RemotePairCandidate, val candidate: LibraryCandidate) : ExistingCopyOutcome

    /** Not found, or not certain: upload as before. [result] is kept with the transfer so it is not looked up again. */
    data class Upload(val result: String) : ExistingCopyOutcome

    /** The answer is not known yet; try the item again at [retryAt]. */
    data class Wait(val retryAt: Long, val message: StoredMessage) : ExistingCopyOutcome
}

/**
 * Looks for an automatic backup item in the iCloud library before it is uploaded. Only exact bytes count: a candidate from
 * the synced catalog ([ExistingCopyMatcher]) is read afresh from iCloud, its original downloaded (never more bytes than
 * the phone file holds) and compared by size and SHA-256. Exactly one exact copy is a match. Several exact copies, a
 * failed read past [CHECK_FAILURE_WINDOW_MILLIS], or no catalog at all, upload as backup always did, because a wrong
 * "already in iCloud" would later let Free up space delete the only copy.
 *
 * A catalog never read for the account makes the item wait, and asks for a read, for up to [FIRST_SYNC_WAIT_MILLIS]
 * after backup was turned on, so a first run does not upload what is already there.
 */
internal class ExistingCopyCheck(
    private val protocol: ICloudPhotoProtocol,
    private val index: CloudLibraryIndex,
    private val now: () -> Long,
) {
    suspend fun check(
        binding: AccountBinding,
        transfer: TransferEntity,
        source: StagedSourceEntity,
        enrolledAtEpochMillis: Long?,
    ): ExistingCopyOutcome {
        val phone = PhoneOriginal(source.displayName, source.byteCount, source.sha256Hex, source.capturedAtEpochMillis)
        notReady(binding, transfer, enrolledAtEpochMillis)?.let { return it }
        val library = runSuspendCatching { index.candidates(binding, phone.byteCount, ExistingCopyMatcher.MAX_SAME_SIZE) }
            .getOrNull() ?: return ExistingCopyOutcome.Upload(RESULT_NO_CATALOG)
        return when (val selection = ExistingCopyMatcher.select(phone, library)) {
            CandidateSelection.None -> ExistingCopyOutcome.Upload(RESULT_NOT_FOUND)
            is CandidateSelection.TooMany -> ExistingCopyOutcome.Upload(RESULT_AMBIGUOUS)
            is CandidateSelection.Check -> compareAll(binding, transfer, phone, selection.candidates)
        }
    }

    /** What to do instead of looking when the catalog cannot answer yet; null when it can be looked in. */
    private suspend fun notReady(binding: AccountBinding, transfer: TransferEntity, enrolledAtEpochMillis: Long?): ExistingCopyOutcome? =
        when (runSuspendCatching { index.state(binding) }.getOrDefault(LibraryIndexState.UNAVAILABLE)) {
            LibraryIndexState.UNAVAILABLE -> ExistingCopyOutcome.Upload(RESULT_NO_CATALOG)
            LibraryIndexState.NOT_SYNCED -> {
                runSuspendCatching { index.requestRefresh() }
                val waitUntil = (enrolledAtEpochMillis ?: transfer.createdAtEpochMillis) + FIRST_SYNC_WAIT_MILLIS
                if (now() < waitUntil) {
                    ExistingCopyOutcome.Wait(minOf(now() + LIBRARY_RETRY_MILLIS, waitUntil), StoredMessage.CHECKING_ICLOUD_LIBRARY_FIRST)
                } else ExistingCopyOutcome.Upload(RESULT_NO_CATALOG)
            }
            // A stale catalog still names items that can be checked afresh; it is brought up to date for the next ones.
            LibraryIndexState.STALE -> {
                runSuspendCatching { index.requestRefresh() }
                null
            }
            LibraryIndexState.READY -> null
        }

    /** Downloads and compares each candidate; exactly one exact copy is a match. */
    private suspend fun compareAll(
        binding: AccountBinding,
        transfer: TransferEntity,
        phone: PhoneOriginal,
        candidates: List<LibraryCandidate>,
    ): ExistingCopyOutcome {
        val exact = mutableListOf<Pair<RemotePairCandidate, LibraryCandidate>>()
        for (candidate in candidates) {
            val compared = runSuspendCatching { compare(binding, phone, candidate) }
            if (compared.isFailure) return failed(transfer)
            compared.getOrNull()?.let { exact += it to candidate }
        }
        return when (exact.size) {
            0 -> ExistingCopyOutcome.Upload(RESULT_NOT_FOUND)
            1 -> ExistingCopyOutcome.Matched(exact.single().first, exact.single().second)
            else -> ExistingCopyOutcome.Upload(RESULT_AMBIGUOUS)
        }
    }

    /** The library item's current pair when its original is exactly [phone]'s bytes; null when it differs or is gone. */
    private suspend fun compare(binding: AccountBinding, phone: PhoneOriginal, candidate: LibraryCandidate): RemotePairCandidate? {
        val pair = protocol.libraryPair(binding, candidate.masterRecordName, candidate.assetRecordName) ?: return null
        if (!pair.relationIsCurrent || pair.masterDeleted || pair.assetDeleted) return null
        val sink = BoundedHashingOutputStream(phone.byteCount)
        val observation = try {
            protocol.streamFreshOriginal(binding, pair, sink)
        } catch (_: OriginalLargerThanExpected) {
            return null
        }
        val same = sink.byteCount == phone.byteCount && observation.byteCount == phone.byteCount &&
            sink.sha256Hex() == phone.sha256Hex && observation.resourceIdentity == pair.originalResourceIdentity
        return pair.takeIf { same }
    }

    /** A read failed: try again shortly, and upload as before once the item has waited long enough. */
    private fun failed(transfer: TransferEntity): ExistingCopyOutcome =
        if (now() < transfer.createdAtEpochMillis + CHECK_FAILURE_WINDOW_MILLIS) {
            ExistingCopyOutcome.Wait(now() + CHECK_RETRY_MILLIS, StoredMessage.CHECKING_ICLOUD_LIBRARY_FIRST)
        } else ExistingCopyOutcome.Upload(RESULT_CHECK_FAILED)

    /** Hashes what it is given and refuses more than [limit] bytes, so a check never downloads more than the phone file. */
    private class BoundedHashingOutputStream(private val limit: Long) : OutputStream() {
        private val hashing = HashingOutputStream()
        val byteCount: Long get() = hashing.byteCount

        override fun write(value: Int) {
            if (hashing.byteCount + 1 > limit) throw OriginalLargerThanExpected()
            hashing.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (hashing.byteCount + length > limit) throw OriginalLargerThanExpected()
            hashing.write(buffer, offset, length)
        }

        fun sha256Hex() = hashing.sha256Hex()
    }

    private class OriginalLargerThanExpected : IOException("The iCloud original is larger than the phone file")

    companion object {
        /** Kept in [TransferEntity.existingCopyCheck]. */
        const val RESULT_MATCHED = "MATCHED"
        const val RESULT_NOT_FOUND = "NOT_FOUND"
        const val RESULT_AMBIGUOUS = "AMBIGUOUS"
        const val RESULT_NO_CATALOG = "NO_CATALOG"
        const val RESULT_CHECK_FAILED = "CHECK_FAILED"

        /** How long backup waits after being turned on for a first library read, before uploading without one. */
        const val FIRST_SYNC_WAIT_MILLIS = 24 * 60 * 60 * 1_000L
        const val LIBRARY_RETRY_MILLIS = 5 * 60 * 1_000L

        /** How long one item keeps retrying a failed iCloud read before it uploads without the answer. */
        const val CHECK_FAILURE_WINDOW_MILLIS = 60 * 60 * 1_000L
        const val CHECK_RETRY_MILLIS = 60 * 1_000L
    }
}
