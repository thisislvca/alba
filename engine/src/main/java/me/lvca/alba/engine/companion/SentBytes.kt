package me.lvca.alba.engine.companion

import me.lvca.alba.engine.model.OneShotUploadSource
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** The size and SHA-256 of exactly the bytes handed to the transport for one upload. */
internal data class SentBytes(val byteCount: Long, val sha256Hex: String)

/** The original changed while it was being sent: the upload stops before its last bytes leave the phone. */
internal class SourceChangedException(message: String) : IOException(message)

/**
 * The upload source as the transport reads it. Every byte is hashed and counted as it is handed over, so what is hashed
 * is what is sent. Before the last bytes are handed over, the stream checks that the source ends where it should, that
 * the hash equals the one recorded when the item was queued, and [beforeLastBytes] (which re-checks a source read in
 * place); any difference throws [SourceChangedException] instead, so the request never completes and Apple never
 * returns a receipt for bytes Alba did not mean to send.
 */
internal class SentBytesSource(
    private val delegate: OneShotUploadSource,
    private val beforeLastBytes: () -> Unit = {},
) : OneShotUploadSource {
    override val byteCount: Long get() = delegate.byteCount
    override val sha256Hex: String get() = delegate.sha256Hex

    /** What was sent, once the transport read every byte; null until then, or when the bytes went in an earlier process. */
    @Volatile
    var sent: SentBytes? = null
        private set

    override fun openOnce(): InputStream = Verifying(delegate.openOnce())

    private inner class Verifying(private val input: InputStream) : InputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var count = 0L
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (finished) return -1
            val remaining = byteCount - count
            if (remaining <= 0L) {
                finish()
                return -1
            }
            val read = input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (read < 0) throw SourceChangedException("The original is shorter than when it was queued")
            digest.update(buffer, offset, read)
            count += read
            // The last bytes are checked before they are handed over.
            if (count == byteCount) finish()
            return read
        }

        private fun finish() {
            finished = true
            if (input.read() >= 0) throw SourceChangedException("The original is longer than when it was queued")
            val hex = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
            if (hex != sha256Hex) throw SourceChangedException("The original's bytes changed since it was queued")
            beforeLastBytes()
            sent = SentBytes(count, hex)
        }

        override fun close() = input.close()
    }

    private companion object {
        const val BYTE_MASK = 0xff
    }
}
