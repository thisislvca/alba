package dev.mela.engine.cache

import android.content.Context
import android.system.Os
import android.system.OsConstants
import dev.mela.engine.model.OneShotUploadSource
import dev.mela.engine.source.DeviceOriginalEvidence
import dev.mela.engine.source.ExactOriginalEvidence
import dev.mela.engine.source.UploadMediaFormat
import dev.mela.engine.source.UploadMediaValidator
import kotlinx.coroutines.currentCoroutineContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StagedUploadFile(
    val path: String,
    val byteCount: Long,
    val sha256Hex: String,
)

data class StagedExactOriginal(
    val ownedFileToken: String,
    val byteCount: Long,
    val sha256Hex: String,
    val evidence: ExactOriginalEvidence,
)

class UploadStagingStore(
    context: Context,
    private val availableBytes: (() -> Long)? = null,
) {
    private val root = File(context.applicationContext.filesDir, "upload_staging").apply {
        check(isDirectory || mkdirs()) { "Could not create private upload staging" }
    }
    private val canonicalRoot = root.canonicalFile

    suspend fun stage(
        transferId: String,
        writer: suspend (OutputStream) -> DeviceOriginalEvidence,
    ): StagedUploadFile = withContext(Dispatchers.IO) {
        val destination = File(canonicalRoot, "${sha256(transferId)}.bin")
        val temporary = File(canonicalRoot, "${sha256(transferId)}.tmp")

        try {
            val evidence = FileOutputStream(temporary).use { output ->
                val result = writer(StorageWriteGuard(output, currentCoroutineContext(), { availableBytes?.invoke() ?: canonicalRoot.usableSpace }))
                output.flush()
                output.fd.sync()
                result
            }
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            StagedUploadFile(
                path = destination.absolutePath,
                byteCount = evidence.byteCount,
                sha256Hex = evidence.sha256Hex,
            )
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    suspend fun stageExact(
        writer: suspend (OutputStream) -> ExactOriginalEvidence,
    ): StagedExactOriginal = withContext(Dispatchers.IO) {
        val token = "${UUID.randomUUID()}.original"
        val destination = File(canonicalRoot, token)
        val temporary = File(canonicalRoot, "$token.tmp")
        check(!destination.exists()) { "Upload stage token collision" }

        try {
            val evidence = FileOutputStream(temporary).use { output ->
                val result = writer(StorageWriteGuard(output, currentCoroutineContext(), { availableBytes?.invoke() ?: canonicalRoot.usableSpace }))
                output.flush()
                output.fd.sync()
                result
            }
            require(evidence.byteCount == temporary.length()) {
                "Exact-original byte count changed while staging"
            }
            val stagedDigest = temporary.inputStream().use(::sha256)
            require(stagedDigest == evidence.sha256Hex) {
                "Exact-original digest changed while staging"
            }
            val header = ByteArray(minOf(temporary.length(), 4096L).toInt())
            java.io.DataInputStream(temporary.inputStream()).use { it.readFully(header) }
            val format = UploadMediaFormat.detect(header)
            require(evidence.formatValidated && evidence.mimeType == format.mimeType) { "Staged media format changed" }
            UploadMediaValidator.validate(temporary, format)
            moveAtomically(temporary, destination)
            syncDirectory()
            StagedExactOriginal(
                ownedFileToken = token,
                byteCount = evidence.byteCount,
                sha256Hex = stagedDigest,
                evidence = evidence,
            )
        } catch (error: Throwable) {
            temporary.delete()
            destination.delete()
            throw error
        }
    }

    suspend fun openVerified(
        ownedFileToken: String,
        expectedByteCount: Long,
        expectedSha256Hex: String,
    ): OneShotUploadSource = withContext(Dispatchers.IO) {
        val file = requireNotNull(ownedTokenFile(ownedFileToken)) {
            "Upload staging token is not owned by Mela"
        }
        require(file.isFile && file.canRead()) { "Staged original is missing" }
        require(file.length() == expectedByteCount) { "Staged original byte count changed" }
        val actualDigest = file.inputStream().use(::sha256)
        require(actualDigest == expectedSha256Hex) { "Staged original digest changed" }
        VerifiedOneShotFile(file, expectedByteCount, expectedSha256Hex)
    }

    fun deleteOwned(ownedFileToken: String): Boolean {
        val file = ownedTokenFile(ownedFileToken) ?: return false
        return !file.exists() || file.delete()
    }

    fun sweepOrphans(
        retainedTokens: Set<String>,
        minimumAgeMillis: Long = ORPHAN_GRACE_MILLIS,
    ): Int {
        require(minimumAgeMillis >= 0L)
        var removed = 0
        val staleCutoff = System.currentTimeMillis() - minimumAgeMillis
        canonicalRoot.listFiles().orEmpty().forEach { file ->
            val owned = file.parentFile == canonicalRoot
            val retained = file.name in retainedTokens
            val oldEnoughToBeOrphaned = file.lastModified() <= staleCutoff
            if (owned && !retained && oldEnoughToBeOrphaned && file.delete()) removed += 1
        }
        return removed
    }

    fun isReadable(path: String?): Boolean = ownedFile(path)?.let { it.isFile && it.canRead() } == true

    private fun ownedFile(path: String?): File? {
        if (path == null) return null
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.parentFile == canonicalRoot }
    }

    private fun ownedTokenFile(token: String): File? {
        if (!token.matches(OWNED_TOKEN_PATTERN)) return null
        val file = runCatching { File(canonicalRoot, token).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.parentFile == canonicalRoot }
    }

    private fun moveAtomically(temporary: File, destination: File) {
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath())
        }
    }

    private fun syncDirectory() {
        runCatching {
            val descriptor = Os.open(canonicalRoot.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        }
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private class VerifiedOneShotFile(
        private val file: File,
        override val byteCount: Long,
        override val sha256Hex: String,
    ) : OneShotUploadSource {
        private val opened = AtomicBoolean(false)

        override fun openOnce(): InputStream {
            check(opened.compareAndSet(false, true)) { "Upload source may be opened only once" }
            return file.inputStream()
        }
    }

    private companion object {
        val OWNED_TOKEN_PATTERN = Regex("[0-9a-fA-F-]{36}\\.(?:jpeg|original)")
        const val ORPHAN_GRACE_MILLIS = 60L * 60L * 1_000L
    }
}
