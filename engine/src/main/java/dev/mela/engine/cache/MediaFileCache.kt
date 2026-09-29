package dev.mela.engine.cache

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import dev.mela.engine.model.LocalMediaCategory
import dev.mela.engine.model.LocalMediaStorage

data class CachePruneResult(
    val evictedPaths: Set<String>,
)

class MediaFileCache(
    context: Context,
    private val maxViewerBytes: Long = DEFAULT_MAX_VIEWER_BYTES,
    private val availableBytes: (File) -> Long = { android.os.StatFs(it.path).availableBytes },
) {
    private val root = File(context.applicationContext.cacheDir, "gallery_media").apply {
        check(isDirectory || mkdirs()) { "Could not create private media cache" }
    }
    private val canonicalRoot = root.canonicalFile
    // Browsed thumbnails are part of the local library, not disposable Android cache.
    private val previewRoot = File(context.applicationContext.filesDir, "gallery_previews").apply {
        check(isDirectory || mkdirs()) { "Could not create thumbnail storage" }
    }.canonicalFile
    private val originalRoot = File(context.applicationContext.filesDir, "gallery_originals").apply {
        check(isDirectory || mkdirs()) { "Could not create offline originals storage" }
    }.canonicalFile

    suspend fun writeAtomically(
        mediaId: String,
        variant: String,
        writer: suspend (OutputStream) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val stem = sha256(mediaId)
        require(variant.matches(VARIANT_PATTERN)) { "Invalid cache variant" }
        val directory = when (variant) {
            "preview" -> previewRoot
            "original", "motion" -> originalRoot
            else -> canonicalRoot
        }
        val destination = File(directory, "$stem-$variant.jpg")
        val temporary = File(directory, "$stem-$variant.tmp")

        try {
            FileOutputStream(temporary).use { output ->
                val limit = when (variant) { "preview" -> 16L * 1024 * 1024; "viewer" -> 64L * 1024 * 1024; else -> Long.MAX_VALUE }
                writer(StorageWriteGuard(output, currentCoroutineContext(), { availableBytes(directory) }, limit))
                currentCoroutineContext().ensureActive()
                output.flush()
                output.fd.sync()
            }
            currentCoroutineContext().ensureActive()
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
            destination
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    /** Adopt an older cached thumbnail without requiring another network request. */
    suspend fun retainPreview(mediaId: String, path: String?): File? = withContext(Dispatchers.IO) {
        val old = ownedFile(path)?.takeIf { it.isFile && it.canRead() } ?: return@withContext null
        if (old.parentFile == previewRoot) return@withContext old
        if (old.parentFile != canonicalRoot || !old.name.endsWith("-preview.jpg")) return@withContext null
        // The old path remains valid until the database publishes the new path; pruning
        // removes the obsolete copy only after a complete, successful catalog refresh.
        writeAtomically(mediaId, "preview") { output -> old.inputStream().use { it.copyTo(output) } }
    }

    suspend fun delete(path: String?) = withContext(Dispatchers.IO) {
        val file = ownedFile(path) ?: return@withContext
        check(!file.exists() || file.delete()) { "Could not remove private cached media" }
    }

    suspend fun pruneTo(allowedPaths: Set<String>): CachePruneResult = withContext(Dispatchers.IO) {
        val allowed = buildSet(allowedPaths.size) {
            allowedPaths.forEach { path -> ownedFile(path)?.path?.let(::add) }
        }
        val retainedViewers = mutableListOf<File>()
        val staleTemporaryCutoff = System.currentTimeMillis() - STALE_TEMP_MILLIS
        allFiles().forEach { file ->
            val isRecentTemporary = file.name.endsWith(".tmp") && file.lastModified() >= staleTemporaryCutoff
            if (isRecentTemporary) return@forEach
            if (file.path !in allowed) {
                check(file.delete()) { "Could not prune private cached media" }
            } else if (file.parentFile == canonicalRoot && file.name.endsWith("-viewer.jpg")) {
                retainedViewers += file
            }
        }

        CachePruneResult(evictedPaths = trimViewers(retainedViewers))
    }

    suspend fun trimViewers(): CachePruneResult = withContext(Dispatchers.IO) {
        CachePruneResult(trimViewers(canonicalRoot.listFiles().orEmpty().filter {
            it.name.endsWith("-viewer.jpg")
        }))
    }

    private fun trimViewers(previews: List<File>): Set<String> {
        val evicted = mutableSetOf<String>()
        var bytes = previews.sumOf(File::length)
        previews.sortedBy(File::lastModified).forEach { file ->
            if (bytes > maxViewerBytes) {
                val size = file.length()
                check(file.delete()) { "Could not trim private preview cache" }
                bytes -= size
                evicted += file.path
            }
        }
        return evicted
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        // The repository excludes active writers before explicit account removal.
        allFiles().forEach { file ->
            check(file.delete()) { "Could not clear private media" }
        }
        CachePruneResult(emptySet())
    }

    suspend fun usage(): LocalMediaStorage = withContext(Dispatchers.IO) {
        fun bytes(directory: File) = directory.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".tmp") }.sumOf(File::length)
        LocalMediaStorage(bytes(previewRoot), bytes(canonicalRoot), bytes(originalRoot), availableBytes(previewRoot))
    }

    /** Caller excludes writers and clears matching catalog paths after deletion. */
    suspend fun clear(category: LocalMediaCategory): Set<String> = withContext(Dispatchers.IO) {
        val directory = when (category) {
            LocalMediaCategory.VIEWERS -> canonicalRoot
            LocalMediaCategory.PREVIEWS -> previewRoot
            LocalMediaCategory.ORIGINALS -> originalRoot
        }
        directory.listFiles().orEmpty().filter { file ->
            // Older cached thumbnails remain previews, even before local migration.
            category != LocalMediaCategory.VIEWERS || file.name.endsWith("-viewer.jpg")
        }.map { file -> check(file.delete()) { "Could not clear private media" }; file.path }.toSet()
    }

    fun isReadable(path: String?): Boolean = ownedFile(path)?.let { it.isFile && it.canRead() } == true

    private fun allFiles(): List<File> = listOf(canonicalRoot, previewRoot, originalRoot)
        .flatMap { it.listFiles().orEmpty().toList() }

    private fun ownedFile(path: String?): File? {
        if (path == null) return null
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        val parent = file.parentFile ?: return null
        return file.takeIf { parent in setOf(canonicalRoot, previewRoot, originalRoot) }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        val VARIANT_PATTERN = Regex("[a-z]+")
        const val DEFAULT_MAX_VIEWER_BYTES = 256L * 1_024L * 1_024L
        const val STALE_TEMP_MILLIS = 60L * 60L * 1_000L
    }
}
