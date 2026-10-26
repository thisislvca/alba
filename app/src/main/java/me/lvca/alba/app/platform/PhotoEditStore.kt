package me.lvca.alba.app.platform

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.*
import me.lvca.alba.app.editing.EditRecipe
import me.lvca.alba.app.editing.RecipeRenderer
import me.lvca.alba.engine.model.*
import java.io.File
import java.io.OutputStream
import kotlin.math.sqrt

/** Editing always publishes a new local JPEG. Cloud and local originals are never overwritten. */
class PhotoEditStore(context: Context, private val repository: OriginalExporter) {
    private val context = context.applicationContext
    suspend fun source(media: GalleryMedia): File = withContext(Dispatchers.IO) {
        require(media.kind != MediaKind.VIDEO)
        val directory = File(context.cacheDir, "edit_sources").apply { mkdirs() }
        directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }?.forEach { it.delete() }
        val file = File.createTempFile("photo-", ".source", directory)
        try {
            file.outputStream().use { target ->
                var bytes = 0L
                val output = object : OutputStream() {
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

/**
 * Decodes the cropped region at full resolution as 8-bit sRGB, applies [recipe] row by row off the main thread,
 * reporting [progress] from 0 to 1, then rotates.
 */
suspend fun renderPhotoEdit(file: File, crop: PhotoCrop, quarterTurns: Int,
    allowSmallerCopy: Boolean = false, memoryBudget: Long = editMemoryBudget(),
    recipe: EditRecipe = EditRecipe(), progress: (Float) -> Unit = {}): Bitmap = withContext(Dispatchers.IO) {
    var longSide = 0
    val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val region = crop.pixels(info.size.width, info.size.height)
        // A 10-bit or HDR source may decode to 8 bytes a pixel, then needs an 8-bit copy. Rotation temporarily owns
        // both the decoded region and the rotated output.
        val wide = mayDecodeWide(info.colorSpace)
        val perPixel = (if (wide) WIDE_BYTES + BYTES else BYTES) + if (quarterTurns % 4 == 0) 0 else BYTES
        val bytes = region.width().toLong() * region.height() * perPixel
        val scale = minOf(1.0, sqrt(memoryBudget.toDouble() / bytes.coerceAtLeast(1)))
        if (scale < 1.0 && !allowSmallerCopy) {
            val width = (region.width() * scale).toInt().coerceAtLeast(1)
            val height = (region.height() * scale).toInt().coerceAtLeast(1)
            throw SmallerPhotoCopyRequired(if (quarterTurns % 2 == 0) width else height, if (quarterTurns % 2 == 0) height else width)
        }
        val targetWidth = (info.size.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (info.size.height * scale).toInt().coerceAtLeast(1)
        longSide = maxOf(targetWidth, targetHeight)
        decoder.setTargetSize(targetWidth, targetHeight)
        decoder.crop = crop.pixels(targetWidth, targetHeight)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = !recipe.isIdentity
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    }.toArgb8888()
    var applied = false
    try {
        // Sharpening reaches as far, relative to the whole photo, as it does on the preview.
        withContext(Dispatchers.Default) { applyRecipe(decoded, recipe, RecipeRenderer.sharpenRadius(longSide), progress) }
        applied = true
    } finally { if (!applied) decoded.recycle() }
    if (quarterTurns % 4 == 0) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height,
        Matrix().apply { postRotate((quarterTurns % 4) * 90f) }, true).also { if (it !== decoded) decoded.recycle() }
}

private const val BYTES = 4
private const val WIDE_BYTES = 8

/** Colour spaces that ImageDecoder may hand back as RGBA_F16 or RGBA_1010102 rather than ARGB_8888. */
private fun mayDecodeWide(colorSpace: ColorSpace?): Boolean = colorSpace != null &&
    colorSpace !in listOf(ColorSpace.Named.SRGB, ColorSpace.Named.DISPLAY_P3, ColorSpace.Named.ADOBE_RGB, ColorSpace.Named.DCI_P3).map(ColorSpace::get)

/** The editor works on 8-bit pixels: a wide decode is copied down once and released. */
private fun Bitmap.toArgb8888(): Bitmap {
    if (config == Bitmap.Config.ARGB_8888) return this
    return try { copy(Bitmap.Config.ARGB_8888, true) } finally { recycle() }
}

/** The editor's preview: at most [side] pixels long, 8-bit sRGB like the saved copy, so colours don't shift on save. */
fun decodeEditPreview(file: File, side: Int): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
    val ratio = minOf(1.0, side.toDouble() / maxOf(info.size.width, info.size.height))
    decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
}.toArgb8888()

/** Applies [recipe] in place, one row at a time, so the photo never needs a second full-size pixel buffer. */
internal suspend fun applyRecipe(bitmap: Bitmap, recipe: EditRecipe, sharpenRadius: Int, progress: (Float) -> Unit = {}) {
    if (recipe.isIdentity) return
    val renderer = RecipeRenderer(recipe, bitmap.width, bitmap.height, sharpenRadius = sharpenRadius)
    if (!renderer.changesPixels) return
    val context = currentCoroutineContext()
    renderer.render(
        read = { y, row -> bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1) },
        write = { y, row -> bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1) },
        checkpoint = { context.ensureActive(); progress(it) },
    )
}

private fun editMemoryBudget(): Long {
    val runtime = Runtime.getRuntime()
    val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
    return minOf(runtime.maxMemory() * 3 / 5, available - 32 * 1024 * 1024).coerceAtLeast(8 * 1024 * 1024)
}

suspend fun saveEditedCopy(context: Context, media: GalleryMedia, bitmap: Bitmap, source: File? = null): Uri = withContext(Dispatchers.IO) {
    val prepared = prepareEditedCopy(context, bitmap, source)
    try { publishEditedCopy(context, media, prepared) } finally { prepared.delete() }
}

/** Complete rendering and metadata before publishing a gallery row. */
private suspend fun prepareEditedCopy(context: Context, bitmap: Bitmap, source: File?): File = withContext(Dispatchers.IO) {
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

private suspend fun publishEditedCopy(context: Context, media: GalleryMedia, prepared: File): Uri =
    publishToMediaStore(context.contentResolver, listOf(MediaStoreCopy(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, media.fileName.substringBeforeLast('.') + "-edited-${System.currentTimeMillis()}.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Alba")
        put(MediaStore.MediaColumns.DATE_TAKEN, media.capturedAtEpochMillis)
    }) { output -> prepared.inputStream().use { it.copyTo(output) } })).single()

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
