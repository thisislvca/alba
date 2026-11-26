package me.lvca.alba.engine.companion

import me.lvca.alba.engine.cache.UploadStagingStore
import me.lvca.alba.engine.database.StagedSourceEntity
import me.lvca.alba.engine.model.OneShotUploadSource
import java.io.Closeable

/**
 * One original opened for one upload. [sending] is what the transport reads: hashed as it is sent, and for an original
 * read in place, checked for changes before its last bytes go. Closing it releases the original.
 */
internal class OpenedOriginal(source: OneShotUploadSource, private val inPlace: InPlaceUpload?) : Closeable {
    val sending = SentBytesSource(source) { inPlace?.requireUnchanged() }

    override fun close() {
        inPlace?.close()
    }
}

/** Where uploads read their originals: a private staged copy, or the phone's own file read in place. */
internal class UploadOriginals(
    private val staging: UploadStagingStore,
    private val inPlace: InPlaceOriginals,
    /** Reads and queues an item again after its original changed; false when that read fails. */
    val restage: suspend (transferId: String) -> Boolean = { false },
) {
    /**
     * Opens [source] for one upload. A staged copy that changed or is missing throws
     * [me.lvca.alba.engine.cache.StagedOriginalUnavailableException]; an original read in place that changed, or can no
     * longer be read, throws [SourceChangedException].
     */
    suspend fun open(source: StagedSourceEntity): OpenedOriginal = if (source.readsInPlace) {
        inPlace.openForUpload(source).let { OpenedOriginal(it, it) }
    } else {
        OpenedOriginal(staging.openVerified(source.ownedFileToken, source.byteCount, source.sha256Hex), null)
    }

    /** Deletes [source]'s staged copy, which can no longer be uploaded. */
    fun discardStaged(source: StagedSourceEntity) {
        staging.deleteOwned(source.ownedFileToken)
    }
}
