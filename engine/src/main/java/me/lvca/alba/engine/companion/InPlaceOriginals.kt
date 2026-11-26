package me.lvca.alba.engine.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.lvca.alba.engine.cache.StagedExactOriginal
import me.lvca.alba.engine.database.StagedSourceEntity
import me.lvca.alba.engine.model.OneShotUploadSource
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.source.CaptureTimes
import me.lvca.alba.engine.source.ExactOriginalEvidence
import me.lvca.alba.engine.source.ExactOriginalSource
import me.lvca.alba.engine.source.InPlaceOriginal
import me.lvca.alba.engine.source.UnsupportedUploadException
import me.lvca.alba.engine.source.UploadMediaFormat
import me.lvca.alba.engine.source.UploadMediaValidator
import java.io.Closeable
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Names a source read in place: no private file stands behind it. */
internal const val IN_PLACE_TOKEN_PREFIX = "in-place:"

/** Whether this source is read where it is on the phone, rather than from a private staged copy. */
internal val StagedSourceEntity.readsInPlace: Boolean get() = ownedFileToken.startsWith(IN_PLACE_TOKEN_PREFIX)

/**
 * Streaming instead of staging. A MediaStore original is queued by reading it once where it is, to hash it, check its
 * format and read its capture time, without copying it into private storage; it is read again, and hashed again as it
 * is sent, when it uploads. A source that cannot be read in place (see [ExactOriginalSource.openInPlace]) is staged as a
 * private copy instead, as before.
 */
internal class InPlaceOriginals(private val originals: ExactOriginalSource) {
    /** Whether MediaStore no longer has [contentUri]; false when it cannot tell. */
    suspend fun isGone(contentUri: String): Boolean =
        contentUri.startsWith("content://media/") && runSuspendCatching { originals.currentRevision(contentUri) == null }.getOrDefault(false)

    /**
     * Inspects [contentUri] in place, as staging inspects a copy. Null when it cannot be read in place, so the caller
     * stages it; throws as staging does when it changed, is empty, or is a format the uploader refuses.
     */
    suspend fun inspect(contentUri: String, expectedRevision: String): StagedExactOriginal? {
        val original = originals.openInPlace(contentUri, expectedRevision) ?: return null
        return withContext(Dispatchers.IO) { original.use { inspect(it) } }
    }

    private suspend fun inspect(original: InPlaceOriginal): StagedExactOriginal? {
        val digest = MessageDigest.getInstance("SHA-256")
        val prefix = ByteArray(FORMAT_PREFIX_BYTES)
        var prefixCount = 0
        var count = 0L
        original.openStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (prefixCount < prefix.size) {
                    val copy = minOf(prefix.size - prefixCount, read)
                    buffer.copyInto(prefix, prefixCount, 0, copy)
                    prefixCount += copy
                }
                digest.update(buffer, 0, read)
                count += read
            }
        }
        require(count > 0L) { StoredMessage.THE_SELECTED_PHOTO_WAS_EMPTY.code }
        requireUnchanged(original, count)
        val format = UploadMediaFormat.detect(prefix.copyOf(prefixCount))
        val captureTime = try {
            UploadMediaValidator.validate(original.descriptor, format)
            CaptureTimes.read(original.descriptor, format.mimeType, original.description.dateTakenEpochMillis, original.description.lastModifiedAtEpochMillis)
        } catch (refused: UnsupportedUploadException) {
            throw refused
        } catch (_: java.io.IOException) {
            // The descriptor could not be read by position: a private copy can be, so the item is staged instead.
            return null
        } catch (_: SecurityException) {
            return null
        }
        requireUnchanged(original, count)
        val sha256Hex = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        return StagedExactOriginal(
            ownedFileToken = "$IN_PLACE_TOKEN_PREFIX${UUID.randomUUID()}",
            byteCount = count,
            sha256Hex = sha256Hex,
            evidence = ExactOriginalEvidence(
                contentUri = original.description.contentUri,
                displayName = format.fileName(original.description.displayName),
                mimeType = format.mimeType,
                lineageKey = original.description.lineageKey,
                sourceRevision = original.description.sourceRevision,
                acquisitionMethod = original.description.acquisitionMethod,
                originalFormatRequested = original.description.originalFormatRequested,
                unredacted = original.description.unredacted,
                formatValidated = true,
                byteCount = count,
                sha256Hex = sha256Hex,
                lastModifiedAtEpochMillis = original.description.lastModifiedAtEpochMillis,
                dateTakenEpochMillis = original.description.dateTakenEpochMillis,
            ),
            captureTime = captureTime,
        )
    }

    private fun requireUnchanged(original: InPlaceOriginal, count: Long) {
        require(count == original.byteCount && original.isUnchanged()) {
            StoredMessage.THIS_PHOTO_CHANGED_WHILE_IT_WAS_BEING_STAGED.code
        }
    }

    /**
     * Opens [source]'s original where it is, for one upload. Throws [SourceChangedException] when it can no longer be
     * read in place or no longer has its queued revision and size; the upload is then redone from a fresh read.
     */
    suspend fun openForUpload(source: StagedSourceEntity): InPlaceUpload {
        val original = runSuspendCatching { originals.openInPlace(source.contentUri, source.sourceRevision) }
            .getOrElse { throw SourceChangedException("The original changed or can no longer be read", it) }
        if (original == null || original.byteCount != source.byteCount) {
            original?.close()
            throw SourceChangedException("The original can no longer be read in place, or its size changed since it was queued")
        }
        return InPlaceUpload(original, source.byteCount, source.sha256Hex)
    }

    private companion object {
        /** Enough of the start of a file to tell its format, as staging reads it. */
        const val FORMAT_PREFIX_BYTES = 4096
    }
}

/**
 * An original read in place for one upload. [SentBytesSource] hashes it as it is sent and calls [requireUnchanged]
 * before the last bytes go, so a file edited mid-upload stops the upload instead of reaching iCloud.
 */
internal class InPlaceUpload(
    private val original: InPlaceOriginal,
    override val byteCount: Long,
    override val sha256Hex: String,
) : OneShotUploadSource, Closeable {
    private val opened = AtomicBoolean(false)

    override fun openOnce(): InputStream {
        check(opened.compareAndSet(false, true)) { "Upload source may be opened only once" }
        return original.openStream()
    }

    fun requireUnchanged() {
        if (!original.isUnchanged()) throw SourceChangedException("The original's size or modification time changed while it was sent")
    }

    override fun close() = original.close()
}
