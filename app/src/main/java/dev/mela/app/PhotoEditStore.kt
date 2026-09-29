package dev.mela.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import dev.mela.engine.model.*
import java.io.File
import kotlinx.coroutines.*

/** Editing always publishes a new local JPEG. Cloud and local originals are never overwritten. */
class PhotoEditStore(context: Context, private val repository: GalleryRepository) {
    private val context = context.applicationContext
    suspend fun source(media: GalleryMedia): File = withContext(Dispatchers.IO) {
        require(media.kind != MediaKind.VIDEO)
        val directory = File(context.cacheDir, "edit_sources").apply { mkdirs() }
        directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }?.forEach { it.delete() }
        val file = File.createTempFile("photo-", ".source", directory)
        try {
            file.outputStream().use { target ->
                var bytes = 0L
                val output = object : java.io.OutputStream() {
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()))
                    override fun write(b: ByteArray, off: Int, len: Int) { bytes += len; check(bytes <= 100_000_000L); target.write(b, off, len) }
                }
                val local = media.linkedDeviceReference ?: media.originalReference?.takeIf { media.origin == MediaOrigin.DEVICE }
                if (local != null) context.contentResolver.openInputStream(local.toUri())!!.use { it.copyTo(output) }
                else repository.exportOriginal(media.id, output)
            }
            ensureActive(); file
        } catch (e: Throwable) { file.delete(); throw e }
    }
}

data class PhotoCrop(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    fun pixels(width: Int, height: Int) = Rect((left * width).toInt().coerceIn(0, width - 1), (top * height).toInt().coerceIn(0, height - 1),
        (right * width).toInt().coerceIn(1, width), (bottom * height).toInt().coerceIn(1, height))
}

class SmallerPhotoCopyRequired(val width: Int, val height: Int) : Exception()

suspend fun renderPhotoEdit(file: File, crop: PhotoCrop, quarterTurns: Int,
    allowSmallerCopy: Boolean = false, memoryBudget: Long = editMemoryBudget()): Bitmap = withContext(Dispatchers.IO) {
    val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val region = crop.pixels(info.size.width, info.size.height)
        // Rotation temporarily owns both the decoded region and the rotated output.
        val bytes = region.width().toLong() * region.height() * if (quarterTurns % 4 == 0) 4L else 8L
        val scale = minOf(1.0, kotlin.math.sqrt(memoryBudget.toDouble() / bytes.coerceAtLeast(1)))
        if (scale < 1.0 && !allowSmallerCopy) {
            val width = (region.width() * scale).toInt().coerceAtLeast(1)
            val height = (region.height() * scale).toInt().coerceAtLeast(1)
            throw SmallerPhotoCopyRequired(if (quarterTurns % 2 == 0) width else height, if (quarterTurns % 2 == 0) height else width)
        }
        val targetWidth = (info.size.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (info.size.height * scale).toInt().coerceAtLeast(1)
        decoder.setTargetSize(targetWidth, targetHeight)
        decoder.crop = crop.pixels(targetWidth, targetHeight)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
    }
    if (quarterTurns % 4 == 0) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height,
        Matrix().apply { postRotate((quarterTurns % 4) * 90f) }, true).also { if (it !== decoded) decoded.recycle() }
}

private fun editMemoryBudget(): Long {
    val runtime = Runtime.getRuntime()
    val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
    return minOf(runtime.maxMemory() * 3 / 5, available - 32 * 1024 * 1024).coerceAtLeast(8 * 1024 * 1024)
}

suspend fun saveEditedCopy(context: Context, media: GalleryMedia, bitmap: Bitmap, document: android.net.Uri? = null,
    source: File? = null): android.net.Uri = withContext(Dispatchers.IO) {
    val prepared = prepareEditedCopy(context, bitmap, source)
    try { publishEditedCopy(context, media, prepared, document) } finally { prepared.delete() }
}

/** Complete rendering and metadata before opening a document picker or publishing a gallery row. */
internal suspend fun prepareEditedCopy(context: Context, bitmap: Bitmap, source: File?): File = withContext(Dispatchers.IO) {
    val directory = File(context.cacheDir, "edit_results").apply { mkdirs() }
    directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }?.forEach { it.delete() }
    val prepared = File.createTempFile("edited-", ".jpg", directory)
    try {
        prepared.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        copyPhotoMetadata(source, prepared, bitmap.width, bitmap.height)
        ensureActive()
        prepared
    } catch (e: Throwable) { prepared.delete(); throw e }
}

internal suspend fun publishEditedCopy(context: Context, media: GalleryMedia, prepared: File,
    document: android.net.Uri? = null): android.net.Uri = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val uri = document ?: run {
        check(Build.VERSION.SDK_INT >= 29)
        checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, media.fileName.substringBeforeLast('.') + "-edited-${System.currentTimeMillis()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Alba")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_TAKEN, media.capturedAtEpochMillis)
        }))
    }
    try {
        checkNotNull(resolver.openOutputStream(uri, "w")).use { output -> prepared.inputStream().use { it.copyTo(output) } }
        ensureActive()
        if (document == null) check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
    } catch (e: Throwable) {
        withContext(NonCancellable) { runCatching { if (document == null) resolver.delete(uri, null, null) else android.provider.DocumentsContract.deleteDocument(resolver, uri) } }
        throw e
    }
    uri
}

private fun copyPhotoMetadata(source: File?, target: File, width: Int, height: Int) {
    val original = source?.let { runCatching { ExifInterface(it) }.getOrNull() }
    val output = ExifInterface(target)
    // Do not copy stale thumbnails, maker notes, orientation, or Live Photo pairing IDs.
    val tags = listOf(ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_TIMESTAMP)
    tags.forEach { tag -> original?.getAttribute(tag)?.let { output.setAttribute(tag, it) } }
    output.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
    output.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
    output.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
    output.saveAttributes()
}
