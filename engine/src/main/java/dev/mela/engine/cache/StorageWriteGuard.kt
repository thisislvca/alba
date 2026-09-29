package dev.mela.engine.cache

import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

class InsufficientStorageException : IOException("Not enough free space on this phone. Free up space and retry.")

/** Cancellation is checked on every chunk; leave a reserve for Android and the database. */
class StorageWriteGuard(
    private val output: OutputStream,
    private val context: CoroutineContext,
    private val availableBytes: () -> Long,
    private val maxBytes: Long = Long.MAX_VALUE,
) : OutputStream() {
    private var written = 0L
    private var untilSpaceCheck = 0L
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        context.ensureActive()
        require(length >= 0 && written <= maxBytes - length) { "Display image is too large" }
        if (untilSpaceCheck <= length) {
            if (availableBytes() - length < RESERVE_BYTES) throw InsufficientStorageException()
            untilSpaceCheck = 256 * 1024L
        }
        output.write(bytes, offset, length)
        written += length
        untilSpaceCheck -= length
    }
    override fun flush() = output.flush()

    companion object { const val RESERVE_BYTES = 64L * 1024 * 1024 }
}
