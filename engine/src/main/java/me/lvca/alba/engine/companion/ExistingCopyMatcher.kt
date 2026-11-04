package me.lvca.alba.engine.companion

import me.lvca.alba.engine.model.LibraryCandidate

/** The phone original backup is about to upload, as staged: its exact size and SHA-256 are known. */
internal data class PhoneOriginal(
    val fileName: String,
    val byteCount: Long,
    val sha256Hex: String,
    val capturedAtEpochMillis: Long?,
)

/** Which iCloud library items are worth downloading to compare with a phone original. */
internal sealed interface CandidateSelection {
    /** Nothing in the library could be the same file: upload. */
    data object None : CandidateSelection

    /** More look alike than Alba downloads for one item: upload, as with any doubt. */
    data class TooMany(val count: Int) : CandidateSelection

    /** Download these, at most [ExistingCopyMatcher.MAX_BYTE_CHECKS], and compare their bytes. */
    data class Check(val candidates: List<LibraryCandidate>) : CandidateSelection
}

/**
 * Picks the iCloud items that could hold the same original as a phone file. A candidate is never a match on its own: it
 * only says which originals to download and compare byte for byte.
 *
 * Apple's own resource fingerprint (`resOriginalFingerprint`, the `fileChecksum` of `resOriginalRes`) would let Alba
 * compare without downloading, but it is the MMCS chunk hash computed by Apple's private CoreMediaStream framework and
 * neither pyicloud nor icloudpd reproduces it (icloudpd uses it only as an opaque name for partial downloads), so Alba
 * does not try to: it matches on what the catalog lists and confirms with the original's SHA-256.
 *
 * A candidate has exactly the phone file's size, and either its file name (ignoring case) or its capture time within
 * [CAPTURE_TOLERANCE_MILLIS]. Those that agree on both are preferred; if more than [MAX_BYTE_CHECKS] remain, the item is
 * ambiguous and uploads as before.
 */
internal object ExistingCopyMatcher {
    /** Originals downloaded at most to decide one phone item. */
    const val MAX_BYTE_CHECKS = 2

    /** Same-size library items read at most for one phone item; more than this many is treated as ambiguous. */
    const val MAX_SAME_SIZE = 50

    /** EXIF capture times have whole seconds; iCloud and MediaStore may round them differently. */
    const val CAPTURE_TOLERANCE_MILLIS = 2_000L

    fun select(phone: PhoneOriginal, library: List<LibraryCandidate>): CandidateSelection {
        if (phone.byteCount <= 0 || phone.sha256Hex.isBlank()) return CandidateSelection.None
        val sameSize = library.filter { it.byteCount == phone.byteCount }.distinctBy { it.masterRecordName to it.assetRecordName }
        if (sameSize.size >= MAX_SAME_SIZE) return CandidateSelection.TooMany(sameSize.size)
        val both = sameSize.filter { sameName(phone, it) && sameCapture(phone, it) }
        val either = sameSize.filter { sameName(phone, it) || sameCapture(phone, it) }
        val chosen = both.ifEmpty { either }
        return when {
            chosen.isEmpty() -> CandidateSelection.None
            chosen.size > MAX_BYTE_CHECKS -> CandidateSelection.TooMany(chosen.size)
            else -> CandidateSelection.Check(chosen)
        }
    }

    private fun sameName(phone: PhoneOriginal, item: LibraryCandidate) =
        phone.fileName.isNotBlank() && phone.fileName.trim().equals(item.fileName.trim(), ignoreCase = true)

    private fun sameCapture(phone: PhoneOriginal, item: LibraryCandidate): Boolean {
        val captured = phone.capturedAtEpochMillis ?: return false
        return kotlin.math.abs(captured - item.capturedAtEpochMillis) <= CAPTURE_TOLERANCE_MILLIS
    }
}
