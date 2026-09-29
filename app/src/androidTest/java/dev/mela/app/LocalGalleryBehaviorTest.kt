package dev.mela.app

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.mela.engine.source.AndroidMediaStoreSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LocalGalleryBehaviorTest {
    @Test fun editsPreserveExifAndRequireConsentBeforeReducingResolution() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File.createTempFile("camera-edit", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }; bitmap.recycle()
        androidx.exifinterface.media.ExifInterface(source).apply {
            setAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL, "Test camera")
            setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, "2026:09:20 12:34:56")
            setLatLong(45.4, 11.9); saveAttributes()
        }
        val original = source.readBytes()
        var uri: android.net.Uri? = null
        try {
            val failure = runCatching { renderPhotoEdit(source, PhotoCrop(), 1, memoryBudget = 1_000_000) }.exceptionOrNull()
            assertTrue(failure is SmallerPhotoCopyRequired)
            val output = renderPhotoEdit(source, PhotoCrop(), 1, allowSmallerCopy = true, memoryBudget = 1_000_000)
            val width = output.width; val height = output.height
            assertTrue(width < 1200 && height < 1600)
            val media = dev.mela.engine.model.GalleryMedia("local", "camera.jpg", 1_790_000_000_000, 1600, 1200,
                dev.mela.engine.model.MediaOrigin.DEVICE, dev.mela.engine.model.MediaAvailability.DEVICE_ORIGINAL, null, null, 0, 0)
            try { uri = saveEditedCopy(context, media, output, source = source) } finally { output.recycle() }
            context.contentResolver.openInputStream(uri!!)!!.use { input ->
                val exif = androidx.exifinterface.media.ExifInterface(input)
                assertEquals("Test camera", exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL))
                assertEquals("2026:09:20 12:34:56", exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL))
                assertEquals(45.4, exif.latLong!![0], .001)
                assertEquals(1, exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 0))
                assertEquals(width, exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_PIXEL_X_DIMENSION, 0))
            }
            assertArrayEquals(original, source.readBytes())
        } finally { uri?.let { context.contentResolver.delete(it, null, null) }; source.delete() }
    }

    @Test fun ordinaryTwelveMegapixelRotationKeepsFullResolution() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File.createTempFile("12mp", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        try {
            val result = renderPhotoEdit(source, PhotoCrop(), 1)
            try { assertEquals(3000, result.width); assertEquals(4000, result.height) } finally { result.recycle() }
        } finally { source.delete() }
    }
    @Test fun deviceScanIncludesVideosFoldersAndRecoverableTrash() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        grantTestMediaPermissions(context)
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "mela-table-stakes-test.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/MelaTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        try {
            context.assets.open("fixture_video.mp4").use { input -> resolver.openOutputStream(uri)!!.use(input::copyTo) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val source = AndroidMediaStoreSource(context)
            val video = source.scanImages().single { it.contentUri == uri.toString() }
            assertEquals("video/mp4", video.mimeType)
            assertEquals("MelaTest", video.deviceFolderName)
            assertTrue(video.id.startsWith("device:video:"))
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
            assertTrue(source.scanImages().single { it.id == video.id }.isTrashed)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }, null, null)
            assertFalse(source.scanImages().single { it.id == video.id }.isTrashed)
        } finally { resolver.delete(uri, null, null) }
    }
    @Test fun cropAndRotationPreserveTheOriginalAndPublishOnlyCompletedCopies() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File.createTempFile("edit-test", ".png", context.cacheDir)
        val original = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        source.outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }; original.recycle()
        val before = source.readBytes()
        try {
            val edited = renderPhotoEdit(source, PhotoCrop(.25f, .25f, .75f, .75f), 1)
            try { assertEquals(40, edited.width); assertEquals(60, edited.height) } finally { edited.recycle() }
            assertArrayEquals(before, source.readBytes())
        } finally { source.delete() }
    }
}
