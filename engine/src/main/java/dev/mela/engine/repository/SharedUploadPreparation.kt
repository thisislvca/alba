package dev.mela.engine.repository

import dev.mela.engine.model.OneShotUploadSource
import dev.mela.engine.source.UploadMediaFormat
import dev.mela.engine.source.UploadMediaValidator
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class PreparedSharedUpload(val format: UploadMediaFormat, val source: OneShotUploadSource)

/** Owns blocking validation and hashing for both shared contribution and save-to-library. */
internal suspend fun prepareSharedUpload(file: File): PreparedSharedUpload = withContext(Dispatchers.IO) {
    val format = UploadMediaFormat.detect(file.inputStream().use { it.readPrefix(4096) })
    UploadMediaValidator.validate(file, format)
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    val hash = digest.digest().joinToString("") { "%02x".format(it) }
    PreparedSharedUpload(format, object : OneShotUploadSource {
        private val opened = AtomicBoolean(false)
        override val byteCount = file.length()
        override val sha256Hex = hash
        override fun openOnce(): java.io.InputStream {
            check(opened.compareAndSet(false, true))
            return file.inputStream()
        }
    })
}

private fun java.io.InputStream.readPrefix(limit: Int): ByteArray {
    require(limit >= 0)
    val buffer = ByteArray(limit)
    var offset = 0
    while (offset < limit) {
        val count = read(buffer, offset, limit - offset)
        if (count < 0) break
        if (count == 0) continue
        offset += count
    }
    return if (offset == limit) buffer else buffer.copyOf(offset)
}
