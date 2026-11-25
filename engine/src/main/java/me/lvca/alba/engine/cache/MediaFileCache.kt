package me.lvca.alba.engine.cache

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.lvca.alba.engine.model.LocalMediaCategory
import me.lvca.alba.engine.model.LocalMediaStorage
import me.lvca.alba.engine.model.OfflinePreviewLimit
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class CachePruneResult(
    val evictedPaths: Set<String>,
)

class MediaFileCache(
    context: Context,
    private val maxViewerBytes: Long = DEFAULT_MAX_VIEWER_BYTES,
    private val availableBytes: (File) -> Long = { android.os.StatFs(it.path).availableBytes },
    /** The most bytes grid previews may take, read at each check; null keeps them all, trimming only for low storage. */
    private val previewLimitBytes: () -> Long? = { OfflinePreviewLimit.DEFAULT.bytes },
    private val clock: () -> Long = System::currentTimeMillis,
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

    /** Previews on screen or in the prefetch window, by file name: never trimmed. */
    @Volatile private var shownPreviews: Set<String> = emptySet()
    private var shownTicket = 0L
    private val windowTickets = AtomicLong()

    /** When each preview was last shown, by file name, until a trim or a flush writes it to the file's mtime. */
    private val pendingAccess = ConcurrentHashMap<String, Long>()

    /** Bytes in previews, kept up to date by preview writes once measured; negative while unknown. */
    private val previewBytes = AtomicLong(UNKNOWN)
    private val previewTrim = Mutex()

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
        // Android may delete the cache directory itself when storage runs low, so recreate it per write.
        check(directory.isDirectory || directory.mkdirs()) { "Could not create private media cache" }
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
            val replacedBytes = destination.length()
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
            if (directory == previewRoot) countPreviewBytes(destination.length() - replacedBytes)
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
        val size = file.length()
        check(!file.exists() || file.delete()) { "Could not remove private cached media" }
        if (file.parentFile == previewRoot) countPreviewBytes(-size)
    }

    suspend fun pruneTo(allowedPaths: Set<String>): CachePruneResult = withContext(Dispatchers.IO) {
        val allowed = buildSet(allowedPaths.size) {
            allowedPaths.forEach { path -> ownedFile(path)?.path?.let(::add) }
        }
        val retainedViewers = mutableListOf<File>()
        val staleTemporaryCutoff = System.currentTimeMillis() - STALE_TEMP_MILLIS
        previewBytes.set(UNKNOWN)
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
        previewBytes.set(UNKNOWN)
        pendingAccess.clear()
        allFiles().forEach { file ->
            check(file.delete()) { "Could not clear private media" }
        }
        CachePruneResult(emptySet())
    }

    suspend fun usage(): LocalMediaStorage = withContext(Dispatchers.IO) {
        fun files(directory: File) = directory.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".tmp") }
        val previews = files(previewRoot)
        LocalMediaStorage(previews.sumOf(File::length), files(canonicalRoot).sumOf(File::length),
            files(originalRoot).sumOf(File::length), availableBytes(previewRoot), previewCount = previews.size)
    }

    /**
     * Records that these items' previews are on screen or in the prefetch window: they count as just used, and no trim
     * removes them until the next window replaces this one. Cheap enough for every window change; the use reaches the
     * files' mtimes in batches, not once per frame.
     */
    suspend fun notePreviewsShown(mediaIds: Collection<String>) {
        // Taken in call order, before the hop, so a late older window never replaces a newer one.
        val ticket = windowTickets.incrementAndGet()
        withContext(Dispatchers.IO) {
            val names = mediaIds.mapTo(HashSet(mediaIds.size)) { previewName(it) }
            val current = synchronized(this@MediaFileCache) {
                (ticket > shownTicket).also { newer ->
                    if (newer) { shownTicket = ticket; shownPreviews = names }
                }
            }
            if (!current) return@withContext
            val now = clock()
            names.forEach { pendingAccess[it] = now }
            if (pendingAccess.size >= ACCESS_FLUSH_BATCH) flushPreviewAccess()
        }
    }

    /** Whether previews are over their limit, or the phone is low on space, so a trim has work to do. Cheap. */
    suspend fun previewsNeedTrim(): Boolean = withContext(Dispatchers.IO) { needsTrim() }

    /**
     * Removes the previews seen longest ago until they fit the limit, with room to spare so the next trim is a while
     * off, and until the phone has [LOW_STORAGE_FREE_BYTES] free again, whatever the limit. Previews on screen or in
     * the prefetch window stay. With [onlyIfNeeded], a trim that another one just made unnecessary lists nothing.
     * Returns the removed paths, so the catalog can forget them.
     */
    suspend fun trimPreviews(onlyIfNeeded: Boolean = false): CachePruneResult = withContext(Dispatchers.IO) {
        previewTrim.withLock {
            if (onlyIfNeeded && !needsTrim()) return@withLock CachePruneResult(emptySet())
            flushPreviewAccess()
            val previews = previewRoot.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(PREVIEW_SUFFIX) }
                .map { PreviewFile(it, it.length(), it.lastModified()) }
            var total = previews.sumOf(PreviewFile::bytes)
            val limit = previewLimitBytes()
            val overLimit = if (limit != null && total > limit) total - limit * TRIM_TARGET_PERCENT / PERCENT else 0L
            val lowStorage = (LOW_STORAGE_FREE_BYTES - availableBytes(previewRoot)).coerceAtLeast(0L)
            var toFree = maxOf(overLimit, lowStorage)
            val shown = shownPreviews
            val evicted = mutableSetOf<String>()
            // Lazily, oldest use first: each preview is deleted only while there is still room to make.
            previews.sortedBy(PreviewFile::lastUsed).asSequence()
                .filter { it.file.name !in shown }
                .takeWhile { toFree > 0L }
                .filter { it.file.delete() }
                .forEach { preview ->
                    total -= preview.bytes
                    toFree -= preview.bytes
                    evicted += preview.file.path
                }
            previewBytes.set(total)
            CachePruneResult(evicted)
        }
    }

    /** Writes recent uses to the previews' mtimes, which order the next trim and outlive the process. */
    suspend fun flushPreviewAccess() = withContext(Dispatchers.IO) {
        pendingAccess.keys.toList().forEach { name ->
            val used = pendingAccess[name] ?: return@forEach
            val file = File(previewRoot, name)
            if (file.isFile && file.lastModified() < used) file.setLastModified(used)
            pendingAccess.remove(name, used)
        }
    }

    private fun needsTrim(): Boolean {
        if (availableBytes(previewRoot) < LOW_STORAGE_FREE_BYTES) return true
        val limit = previewLimitBytes() ?: return false
        return measuredPreviewBytes() > limit
    }

    private fun measuredPreviewBytes(): Long = previewBytes.get().takeIf { it >= 0 } ?: previewRoot.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(PREVIEW_SUFFIX) }.sumOf(File::length).also { previewBytes.set(it) }

    private fun countPreviewBytes(change: Long) {
        previewBytes.getAndUpdate { bytes -> if (bytes < 0) bytes else (bytes + change).coerceAtLeast(0L) }
    }

    private fun previewName(mediaId: String) = "${sha256(mediaId)}$PREVIEW_SUFFIX"

    private class PreviewFile(val file: File, val bytes: Long, val lastUsed: Long)

    /** Caller excludes writers and clears matching catalog paths after deletion. */
    suspend fun clear(category: LocalMediaCategory): Set<String> = withContext(Dispatchers.IO) {
        val directory = when (category) {
            LocalMediaCategory.VIEWERS -> canonicalRoot
            LocalMediaCategory.PREVIEWS -> previewRoot
            LocalMediaCategory.ORIGINALS -> originalRoot
        }
        if (directory == previewRoot) previewBytes.set(UNKNOWN)
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
        const val UNKNOWN = -1L
        const val PREVIEW_SUFFIX = "-preview.jpg"

        /** A trim over the limit goes down to this share of it, so trims come in batches rather than per preview. */
        const val TRIM_TARGET_PERCENT = 90L
        const val PERCENT = 100L

        /** Uses kept in memory before they are written to the files, so a long scroll is not lost to process death. */
        const val ACCESS_FLUSH_BATCH = 512

        /** Below this much free space, previews make room: twice the reserve every write keeps for Android. */
        const val LOW_STORAGE_FREE_BYTES = 2 * StorageWriteGuard.RESERVE_BYTES
    }
}
