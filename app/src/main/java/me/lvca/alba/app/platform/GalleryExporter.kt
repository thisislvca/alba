package me.lvca.alba.app.platform

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.lvca.alba.app.R
import me.lvca.alba.engine.cache.StorageWriteGuard
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaKind
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.engine.model.OriginalExporter
import me.lvca.alba.engine.model.StoredMessage
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Publishes new copies only; it never modifies the source or asks for broad storage access. */
class GalleryExporter(context: Context, private val repository: OriginalExporter) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    /** Only completed copies in this directory are exposed to the chosen share target. */
    suspend fun prepareShare(items: List<GalleryMedia>): Intent = withContext(Dispatchers.IO) {
        require(items.isNotEmpty() && items.size <= 50) { StoredMessage.CHOOSE_BETWEEN_1_AND_50_PHOTOS_TO_SHARE_AT_ONCE.code }
        val root = File(appContext.cacheDir, "shares").apply { mkdirs() }
        val expiry = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        root.listFiles()?.filter { it.lastModified() < expiry }?.forEach { it.deleteRecursively() }
        val directory = File(root, UUID.randomUUID().toString()).apply { check(mkdirs()) }
        try {
            val context = currentCoroutineContext()
            var total = 0L
            var nextSpaceCheck = 0L
            val uris = items.mapIndexed { index, media ->
                currentCoroutineContext().ensureActive()
                val file = File(directory, "${index + 1}-${exportName(media)}")
                file.outputStream().use { raw ->
                    val output = object : OutputStream() {
                        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            context.ensureActive()
                            total += length
                            check(total <= 2_000_000_000L) { StoredMessage.SHARE_UP_TO_2_GB_AT_A_TIME_TRY_FEWER_ITEMS.code }
                            if (total >= nextSpaceCheck) {
                                // Leave headroom without evicting unrelated application caches for a share.
                                check(StatFs(directory.path).availableBytes > 16_000_000L) {
                                    StoredMessage.NOT_ENOUGH_SPACE_TO_PREPARE_THIS_SHARE_TRY_FEWER_OR_SMALLER_ITEMS.code
                                }
                                nextSpaceCheck = total + 1_000_000L
                            }
                            raw.write(bytes, offset, length)
                        }
                    }
                    if (media.origin == MediaOrigin.DEVICE) {
                        val uri = requireNotNull(media.originalReference) { "Phone photo is no longer available." }.toUri()
                        require(uri.scheme == "content")
                        checkNotNull(resolver.openInputStream(uri)).use { it.copyTo(output) }
                    } else repository.exportOriginal(media.id, output)
                }
                FileProvider.getUriForFile(appContext, "${appContext.packageName}.shares", file)
            }
            currentCoroutineContext().ensureActive()
            val mimes = items.map { it.mimeType }.distinct()
            val mime = if (mimes.size == 1) mimes.single() else if (mimes.all { it.startsWith("image/") }) "image/*"
                else if (mimes.all { it.startsWith("video/") }) "video/*" else "*/*"
            Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(resolver, appContext.getString(R.string.photos), uris.first()).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single())
                else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    suspend fun clearShares() = withContext(Dispatchers.IO) {
        File(appContext.cacheDir, "shares").deleteRecursively()
        Unit
    }

    suspend fun saveToGallery(media: GalleryMedia) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) error(StoredMessage.USE_EXPORT_ORIGINAL_ON_ANDROID_9.code)
        // Both Live Photo resources are written before either is made visible.
        val copies = (if (media.kind == MediaKind.LIVE_PHOTO) listOf(false, true) else listOf(false)).map { motion ->
            val mime = if (motion) media.motionMimeType else media.mimeType
            val collection = if (mime.startsWith("video/")) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            MediaStoreCopy(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, exportName(media, motion))
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (mime.startsWith("video/")) "Movies/Alba" else "Pictures/Alba")
                put(MediaStore.MediaColumns.DATE_TAKEN, media.capturedAtEpochMillis)
            }) { output ->
                repository.exportOriginal(media.id, StorageWriteGuard(output, currentCoroutineContext(), {
                    StatFs((appContext.getExternalFilesDir(null) ?: appContext.filesDir).path).availableBytes
                }), motion)
            }
        }
        publishToMediaStore(resolver, copies)
        Unit
    }

    suspend fun exportDocument(media: GalleryMedia, uri: Uri, livePair: Boolean) = withContext(Dispatchers.IO) {
        try {
            checkNotNull(resolver.openOutputStream(uri, "wt")) { StoredMessage.COULD_NOT_OPEN_THE_EXPORT_DESTINATION.code }.use { output ->
                if (livePair) writeLivePhotoArchive(media, output) else repository.exportOriginal(media.id, output)
            }
        } catch (error: Throwable) {
            // Remove only the new document created for this export; never touch its source.
            withContext(NonCancellable) { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
            throw error
        }
    }

    internal suspend fun writeLivePhotoArchive(media: GalleryMedia, output: OutputStream) {
        require(media.kind == MediaKind.LIVE_PHOTO)
        ZipOutputStream(output).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
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
