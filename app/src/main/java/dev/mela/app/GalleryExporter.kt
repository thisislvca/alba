package dev.mela.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.provider.MediaStore
import dev.mela.engine.model.GalleryMedia
import dev.mela.engine.model.GalleryRepository
import dev.mela.engine.model.MediaKind
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import dev.mela.engine.cache.StorageWriteGuard

/** Publishes new copies only; it never modifies the source or asks for broad storage access. */
class GalleryExporter(context: Context, private val repository: GalleryRepository) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    /** Only completed copies in this directory are exposed to the chosen share target. */
    suspend fun prepareShare(items: List<GalleryMedia>): android.content.Intent = withContext(Dispatchers.IO) {
        require(items.isNotEmpty() && items.size <= 50) { "Select between 1 and 50 items to share." }
        val root = java.io.File(appContext.cacheDir, "shares").apply { mkdirs() }
        val expiry = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        root.listFiles()?.filter { it.lastModified() < expiry }?.forEach { it.deleteRecursively() }
        val directory = java.io.File(root, java.util.UUID.randomUUID().toString()).apply { check(mkdirs()) }
        try {
            val context = kotlinx.coroutines.currentCoroutineContext()
            var total = 0L
            var nextSpaceCheck = 0L
            val uris = items.mapIndexed { index, media ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val file = java.io.File(directory, "${index + 1}-${exportName(media)}")
                file.outputStream().use { raw ->
                    val output = object : java.io.OutputStream() {
                        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            context.ensureActive()
                            total += length
                            check(total <= 2_000_000_000L) { "Share up to 2 GB at a time. Try fewer items." }
                            if (total >= nextSpaceCheck) {
                                // Leave headroom without evicting unrelated application caches for a share.
                                check(android.os.StatFs(directory.path).availableBytes > 16_000_000L) {
                                    "Not enough space to prepare this share. Try fewer or smaller items."
                                }
                                nextSpaceCheck = total + 1_000_000L
                            }
                            raw.write(bytes, offset, length)
                        }
                    }
                    if (media.origin == dev.mela.engine.model.MediaOrigin.DEVICE) {
                        val uri = requireNotNull(media.originalReference) { "Phone photo is no longer available." }.toUri()
                        require(uri.scheme == "content")
                        checkNotNull(resolver.openInputStream(uri)).use { it.copyTo(output) }
                    } else repository.exportOriginal(media.id, output)
                }
                androidx.core.content.FileProvider.getUriForFile(appContext, "${appContext.packageName}.shares", file)
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val mimes = items.map { it.mimeType }.distinct()
            val mime = if (mimes.size == 1) mimes.single() else if (mimes.all { it.startsWith("image/") }) "image/*"
                else if (mimes.all { it.startsWith("video/") }) "video/*" else "*/*"
            android.content.Intent(if (uris.size == 1) android.content.Intent.ACTION_SEND else android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(resolver, appContext.getString(R.string.photos), uris.first()).apply {
                    uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
                }
                if (uris.size == 1) putExtra(android.content.Intent.EXTRA_STREAM, uris.single())
                else putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
            }
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    suspend fun clearShares() = withContext(Dispatchers.IO) {
        java.io.File(appContext.cacheDir, "shares").deleteRecursively()
        Unit
    }

    suspend fun saveToGallery(media: GalleryMedia) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) error("Use Export original on Android 9.")
        val pending = mutableListOf<Uri>()
        try {
            for (motion in if (media.kind == MediaKind.LIVE_PHOTO) listOf(false, true) else listOf(false)) {
                val mime = if (motion) media.motionMimeType else media.mimeType
                val collection = if (mime.startsWith("video/")) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = checkNotNull(resolver.insert(collection, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, exportName(media, motion))
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, if (mime.startsWith("video/")) "Movies/Alba" else "Pictures/Alba")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    put(MediaStore.MediaColumns.DATE_TAKEN, media.capturedAtEpochMillis)
                })) { "Android could not create a gallery copy." }
                pending += uri
                checkNotNull(resolver.openOutputStream(uri, "w")).use { output ->
                    repository.exportOriginal(media.id, StorageWriteGuard(output, currentCoroutineContext(), {
                        android.os.StatFs((appContext.getExternalFilesDir(null) ?: appContext.filesDir).path).availableBytes
                    }), motion)
                }
            }
            currentCoroutineContext().ensureActive()
            // Both Live Photo resources are written before either is made visible.
            pending.forEach { uri ->
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
                    "Android could not publish the gallery copy."
                }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) { pending.forEach { uri -> runCatching { resolver.delete(uri, null, null) } } }
            throw error
        }
    }

    suspend fun exportDocument(media: GalleryMedia, uri: Uri, livePair: Boolean) = withContext(Dispatchers.IO) {
        try {
            checkNotNull(resolver.openOutputStream(uri, "wt")) { "Could not open the export destination." }.use { output ->
                if (livePair) writeLivePhotoArchive(media, output) else repository.exportOriginal(media.id, output)
            }
        } catch (error: Throwable) {
            // Remove only the new document created for this export; never touch its source.
            withContext(NonCancellable) { runCatching { android.provider.DocumentsContract.deleteDocument(resolver, uri) } }
            throw error
        }
    }

    internal suspend fun writeLivePhotoArchive(media: GalleryMedia, output: OutputStream) {
        require(media.kind == MediaKind.LIVE_PHOTO)
        ZipOutputStream(output).use { zip ->
            zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
            for (motion in listOf(false, true)) {
                zip.putNextEntry(ZipEntry(exportName(media, motion)).apply { time = media.capturedAtEpochMillis })
                repository.exportOriginal(media.id, zip, motion)
                zip.closeEntry()
            }
        }
    }
}

internal fun exportName(media: GalleryMedia, motion: Boolean = false): String {
    val safe = media.fileName.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\p{Cntrl}]"), "_").take(180).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "Photo"
    val stem = safe.substringBeforeLast('.', safe).ifBlank { "Photo" }
    return if (motion) "$stem.${if (media.motionMimeType == "video/mp4") "mp4" else "mov"}" else safe
}
