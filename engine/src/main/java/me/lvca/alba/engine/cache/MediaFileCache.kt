package me.lvca.alba.engine.cache

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.lvca.alba.engine.model.CacheLimit
import me.lvca.alba.engine.model.LocalMediaCategory
import me.lvca.alba.engine.model.LocalMediaStorage
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

/**
 * Alba's private copies of iCloud media, in two budgets.
 *
 * - The offline set: thumbnails of the newest library photos that fit the thumbnail budget, which the repository names
 *   with [setOfflineThumbnails]. They are never swapped out for anything; only a phone low on space removes them,
 *   oldest photo first, after everything else.
 * - The cache: full-size images and thumbnails of photos outside the set, under [cacheLimitBytes]. What was opened or
 *   scrolled to longest ago goes first.
 *
 * Offline originals the user chose to keep are in neither: nothing here removes them.
 */
class MediaFileCache(
    context: Context,
    private val availableBytes: (File) -> Long = { android.os.StatFs(it.path).availableBytes },
    /** The cache's limit, read at each check. */
    private val cacheLimitBytes: () -> Long = { CacheLimit.DEFAULT.bytes },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val root = File(context.applicationContext.cacheDir, "gallery_media").apply {
        check(isDirectory || mkdirs()) { "Could not create private media cache" }
    }
    private val canonicalRoot = root.canonicalFile

    // Browsed thumbnails are part of the local library, not disposable Android cache.
    private val filesDir = context.applicationContext.filesDir
    private val previewRoot = File(filesDir, PREVIEW_FOLDER).apply {
        check(isDirectory || mkdirs()) { "Could not create thumbnail storage" }
    }.canonicalFile
    private val originalRoot = File(filesDir, ORIGINAL_FOLDER).apply {
        check(isDirectory || mkdirs()) { "Could not create offline originals storage" }
    }.canonicalFile

    /** The offline set's thumbnail file names, newest photo first; null until the repository names it. */
    @Volatile private var offlineSet: OfflineSet? = null

    /** Media on screen or in the prefetch window, by file stem: never trimmed. */
    @Volatile private var shownStems: Set<String> = emptySet()
    private var shownTicket = 0L
    private val windowTickets = AtomicLong()

    /** When each file was last used, by path, until a trim or a flush writes it to the file's mtime. */
    private val pendingAccess = ConcurrentHashMap<String, Long>()

    /** Bytes in the cache, kept up to date by writes once measured; negative while unknown. */
    private val cacheBytes = AtomicLong(UNKNOWN)
    private val trimLock = Mutex()

    private class OfflineSet(val newestFirst: List<String>) {
        val names: Set<String> = newestFirst.toHashSet()
    }

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
            if (isCached(destination)) countCacheBytes(destination.length() - replacedBytes)
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
        if (old.parentFile != canonicalRoot || !old.name.endsWith(PREVIEW_SUFFIX)) return@withContext null
        // The old path remains valid until the database publishes the new path; pruning
        // removes the obsolete copy only after a complete, successful catalog refresh.
        writeAtomically(mediaId, "preview") { output -> old.inputStream().use { it.copyTo(output) } }
    }

    suspend fun delete(path: String?) = withContext(Dispatchers.IO) {
        val file = ownedFile(path) ?: return@withContext
        val size = file.length()
        val cached = isCached(file)
        check(!file.exists() || file.delete()) { "Could not remove private cached media" }
        if (cached) countCacheBytes(-size)
    }

    /** Removes every file outside [allowedPaths]; the cache's own limit is applied afterwards by [trimCache]. */
    suspend fun pruneTo(allowedPaths: Set<String>): CachePruneResult = withContext(Dispatchers.IO) {
        val allowed = buildSet(allowedPaths.size) {
            allowedPaths.forEach { path -> ownedFile(path)?.path?.let(::add) }
        }
        val staleTemporaryCutoff = clock() - STALE_TEMP_MILLIS
        cacheBytes.set(UNKNOWN)
        allFiles().forEach { file ->
            val isRecentTemporary = file.name.endsWith(".tmp") && file.lastModified() >= staleTemporaryCutoff
            if (isRecentTemporary) return@forEach
            if (file.path !in allowed) check(file.delete()) { "Could not prune private cached media" }
        }
        CachePruneResult(emptySet())
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        // The repository excludes active writers before explicit account removal.
        cacheBytes.set(UNKNOWN)
        pendingAccess.clear()
        allFiles().forEach { file ->
            check(file.delete()) { "Could not clear private media" }
        }
        CachePruneResult(emptySet())
    }

    suspend fun usage(): LocalMediaStorage = withContext(Dispatchers.IO) {
        fun files(directory: File) = directory.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".tmp") }
        val previews = files(previewRoot)
        val viewers = files(canonicalRoot)
        val set = offlineSet
        val inSet = previews.filter { set == null || it.name in set.names }
        LocalMediaStorage(
            previewBytes = previews.sumOf(File::length),
            viewerBytes = viewers.sumOf(File::length),
            originalBytes = files(originalRoot).sumOf(File::length),
            availableBytes = availableBytes(previewRoot),
            previewCount = previews.size,
            offlineThumbnailBytes = inSet.sumOf(File::length),
            cacheBytes = (previews + viewers).filter(::isCached).sumOf(File::length),
        )
    }

    /**
     * Names the offline set: the thumbnails of these photos, newest first. Their thumbnails leave the cache's limit;
     * thumbnails of photos that dropped out of the set join it.
     */
    fun setOfflineThumbnails(mediaIdsNewestFirst: List<String>) {
        offlineSet = OfflineSet(mediaIdsNewestFirst.map(::previewName))
        cacheBytes.set(UNKNOWN)
    }

    /** Forgets the offline set, as when the library is cleared; until it is named again, every thumbnail stays. */
    fun forgetOfflineThumbnails() {
        offlineSet = null
        cacheBytes.set(UNKNOWN)
    }

    val hasOfflineSet: Boolean get() = offlineSet != null

    /**
     * Records that these items are on screen or in the prefetch window: they count as just used, and no trim removes
     * them until the next window replaces this one. Cheap enough for every window change; the use reaches the files'
     * mtimes in batches, not once per frame.
     */
    suspend fun notePreviewsShown(mediaIds: Collection<String>) {
        // Taken in call order, before the hop, so a late older window never replaces a newer one.
        val ticket = windowTickets.incrementAndGet()
        withContext(Dispatchers.IO) {
            val stems = mediaIds.mapTo(HashSet(mediaIds.size)) { sha256(it) }
            val current = synchronized(this@MediaFileCache) {
                (ticket > shownTicket).also { newer ->
                    if (newer) { shownTicket = ticket; shownStems = stems }
                }
            }
            if (!current) return@withContext
            val now = clock()
            stems.forEach { pendingAccess[File(previewRoot, "$it$PREVIEW_SUFFIX").path] = now }
            if (pendingAccess.size >= ACCESS_FLUSH_BATCH) flushAccess()
        }
    }

    /** Records that a full-size image was opened again, for the cache's order. */
    fun noteUsed(path: String?) {
        val file = ownedFile(path) ?: return
        pendingAccess[file.path] = clock()
    }

    /**
     * Marks a thumbnail saved in the background as never seen, so it never outranks what the user looked at; if its
     * photo leaves the offline set, it is the first to go.
     */
    suspend fun markUnseen(mediaId: String, file: File) = withContext(Dispatchers.IO) {
        val stem = sha256(mediaId)
        if (stem in shownStems || pendingAccess.containsKey(file.path)) return@withContext
        file.setLastModified(NEVER_SEEN_MILLIS)
    }

    /** Whether the cache is over its limit, or the phone is low on space, so a trim has work to do. Cheap. */
    suspend fun needsTrim(): Boolean = withContext(Dispatchers.IO) { trimDue() }

    /** Free space left before the low-storage reserve. */
    suspend fun roomBeforeReserve(): Long = withContext(Dispatchers.IO) { availableBytes(previewRoot) - LOW_STORAGE_FREE_BYTES }

    /**
     * Brings the cache within its limit, with room to spare so the next trim is a while off, removing what was used
     * longest ago. On a phone with less than [LOW_STORAGE_FREE_BYTES] free it then frees more: the rest of the cache
     * first, then the offline set's thumbnails, oldest photo first. What is on screen or in the prefetch window stays.
     * With [onlyIfNeeded], a trim another one just made unnecessary lists nothing. Returns the removed paths, so the
     * catalog can forget them.
     */
    suspend fun trimCache(onlyIfNeeded: Boolean = false): CachePruneResult = withContext(Dispatchers.IO) {
        trimLock.withLock {
            if (onlyIfNeeded && !trimDue()) return@withLock CachePruneResult(emptySet())
            flushAccess()
            val cached = cachedFiles().map { CachedFile(it, it.length(), it.lastModified()) }
            var total = cached.sumOf(CachedFile::bytes)
            val limit = cacheLimitBytes()
            val overLimit = if (total > limit) total - limit * TRIM_TARGET_PERCENT / PERCENT else 0L
            var lowStorage = (LOW_STORAGE_FREE_BYTES - availableBytes(previewRoot)).coerceAtLeast(0L)
            var toFree = maxOf(overLimit, lowStorage)
            val shown = shownStems
            fun shown(file: File) = file.name.substringBefore('-') in shown
            val evicted = mutableSetOf<String>()
            // Lazily, oldest use first: each file is deleted only while there is still room to make.
            cached.sortedBy(CachedFile::lastUsed).asSequence()
                .filterNot { shown(it.file) }
                .takeWhile { toFree > 0L }
                .filter { it.file.delete() }
                .forEach { file ->
                    total -= file.bytes
                    toFree -= file.bytes
                    lowStorage -= file.bytes
                    evicted += file.file.path
                }
            cacheBytes.set(total)
            // Only real pressure reaches the offline set, and then its oldest photos first.
            offlineSet?.takeIf { lowStorage > 0L }?.newestFirst?.asReversed()?.asSequence()
                ?.map { File(previewRoot, it) }
                ?.filter { it.isFile && !shown(it) }
                ?.takeWhile { lowStorage > 0L }
                ?.forEach { file ->
                    val size = file.length()
                    if (file.delete()) { lowStorage -= size; evicted += file.path }
                }
            CachePruneResult(evicted)
        }
    }

    /** Writes recent uses to the files' mtimes, which order the next trim and outlive the process. */
    suspend fun flushPreviewAccess() = withContext(Dispatchers.IO) { flushAccess() }

    private fun flushAccess() {
        pendingAccess.keys.toList().forEach { path ->
            val used = pendingAccess[path] ?: return@forEach
            val file = File(path)
            if (file.isFile && file.lastModified() < used) file.setLastModified(used)
            pendingAccess.remove(path, used)
        }
    }

    private fun trimDue(): Boolean =
        availableBytes(previewRoot) < LOW_STORAGE_FREE_BYTES || measuredCacheBytes() > cacheLimitBytes()

    private fun measuredCacheBytes(): Long = cacheBytes.get().takeIf { it >= 0 }
        ?: cachedFiles().sumOf(File::length).also { cacheBytes.set(it) }

    private fun countCacheBytes(change: Long) {
        cacheBytes.getAndUpdate { bytes -> if (bytes < 0) bytes else (bytes + change).coerceAtLeast(0L) }
    }

    /** Full-size images, and thumbnails outside the offline set once it is named. */
    private fun cachedFiles(): List<File> = listOf(canonicalRoot, previewRoot)
        .flatMap { it.listFiles().orEmpty().toList() }
        .filter { it.isFile && isCached(it) }

    private fun isCached(file: File): Boolean = when (file.parentFile) {
        canonicalRoot -> file.name.endsWith(VIEWER_SUFFIX)
        previewRoot -> file.name.endsWith(PREVIEW_SUFFIX) && offlineSet?.let { file.name !in it.names } == true
        else -> false
    }

    private fun previewName(mediaId: String) = "${sha256(mediaId)}$PREVIEW_SUFFIX"

    private class CachedFile(val file: File, val bytes: Long, val lastUsed: Long)

    /** Caller excludes writers and clears matching catalog paths after deletion. */
    suspend fun clear(category: LocalMediaCategory): Set<String> = withContext(Dispatchers.IO) {
        val directory = when (category) {
            LocalMediaCategory.VIEWERS -> canonicalRoot
            LocalMediaCategory.PREVIEWS -> previewRoot
            LocalMediaCategory.ORIGINALS -> originalRoot
        }
        cacheBytes.set(UNKNOWN)
        directory.listFiles().orEmpty().filter { file ->
            // Older cached thumbnails remain previews, even before local migration.
            category != LocalMediaCategory.VIEWERS || file.name.endsWith(VIEWER_SUFFIX)
        }.map { file -> check(file.delete()) { "Could not clear private media" }; file.path }.toSet()
    }

    fun isReadable(path: String?): Boolean = ownedFile(path)?.let { it.isFile && it.canRead() } == true

    /**
     * [isReadable] for checking thousands of paths at once, as loading the library does: one listing of each folder
     * instead of resolving and probing every path, which took most of the library's load. A path the cache wrote is
     * already canonical and is answered from the listing; any other path is probed as [isReadable] does.
     */
    fun readableSnapshot(): (String?) -> Boolean {
        val listings = listOf(canonicalRoot, previewRoot, originalRoot).associateWith { it.list().orEmpty().toHashSet() }
        // A folder under its canonical name and the name the app gave it, which differ where a parent is a link.
        val listed = HashMap<String, Set<String>>()
        listOf(root to canonicalRoot, File(filesDir, PREVIEW_FOLDER) to previewRoot, File(filesDir, ORIGINAL_FOLDER) to originalRoot)
            .forEach { (given, canonical) ->
                listed[canonical.path] = listings.getValue(canonical)
                listed[given.absolutePath] = listings.getValue(canonical)
            }
        return { path ->
            if (path == null) false
            else {
                val file = File(path)
                val names = file.parent?.let(listed::get)
                if (names != null && file.isAbsolute && file.normalize().path == path) file.name in names else isReadable(path)
            }
        }
    }

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

    companion object {
        private val VARIANT_PATTERN = Regex("[a-z]+")
        private const val PREVIEW_FOLDER = "gallery_previews"
        private const val ORIGINAL_FOLDER = "gallery_originals"
        private const val STALE_TEMP_MILLIS = 60L * 60L * 1_000L
        private const val UNKNOWN = -1L
        private const val PREVIEW_SUFFIX = "-preview.jpg"
        private const val VIEWER_SUFFIX = "-viewer.jpg"

        /** A trim over the limit goes down to this share of it, so trims come in batches rather than per file. */
        private const val TRIM_TARGET_PERCENT = 90L
        private const val PERCENT = 100L

        /** Uses kept in memory before they are written to the files, so a long scroll is not lost to process death. */
        private const val ACCESS_FLUSH_BATCH = 512

        /** The mtime of a thumbnail saved in the background and not seen yet: older than any real use. */
        private const val NEVER_SEEN_MILLIS = 1_000L

        /** Below this much free space, the cache and then the offline set make room: twice every write's reserve. */
        const val LOW_STORAGE_FREE_BYTES = 2 * StorageWriteGuard.RESERVE_BYTES
    }
}
