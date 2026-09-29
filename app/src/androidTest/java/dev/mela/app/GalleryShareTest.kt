package dev.mela.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import dev.mela.engine.model.GalleryRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GalleryShareTest {
    @Test fun sharesExactBytesThroughReadOnlyScopedUrisWithMixedMimeAndNoFilenameCollisions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = (context as MelaApplication).graph.galleryRepository
        repository.refresh(false)
        val media = repository.observeGallery().first()
        val photo = media.first { it.id == "fixture:icloud:0001" }
        val video = media.first { it.id == "fixture:icloud:0019" }
        val exporter = GalleryExporter(context, repository)
        val intent = exporter.prepareShare(listOf(photo.copy(fileName = "../same.jpg"), video.copy(fileName = "same.jpg")))
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("*/*", intent.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        val clip = requireNotNull(intent.clipData)
        assertEquals(2, clip.itemCount)
        val uris = (0 until 2).map { clip.getItemAt(it).uri }
        assertNotEquals(uris[0], uris[1])
        for ((index, item) in listOf(photo, video).withIndex()) {
            assertEquals("content", uris[index].scheme)
            assertEquals("${context.packageName}.shares", uris[index].authority)
            val expected = ByteArrayOutputStream().also { repository.exportOriginal(item.id, it) }.toByteArray()
            assertArrayEquals(expected, context.contentResolver.openInputStream(uris[index])!!.use { it.readBytes() })
        }
        val single = exporter.prepareShare(listOf(photo))
        assertEquals(Intent.ACTION_SEND, single.action)
        assertEquals(photo.mimeType, single.type)
        assertEquals(single.clipData!!.getItemAt(0).uri, single.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertTrue(runCatching { FileProvider.getUriForFile(context, "${context.packageName}.shares", File(context.cacheDir, "private-session")) }.isFailure)
        val provider = context.packageManager.resolveContentProvider("${context.packageName}.shares", 0)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        exporter.clearShares()
    }

    @Test fun phonePhotoSharesGrantedContentWithoutCloudCalls() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = (context as MelaApplication).graph.galleryRepository
        val uri = requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "mela-share-test.png")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
            }))
        try {
            val expected = ByteArrayOutputStream().also { output ->
                val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
            }.toByteArray()
            context.contentResolver.openOutputStream(uri)!!.use { it.write(expected) }
            val photo = dev.mela.engine.model.GalleryMedia("device:test", "mela-share-test.png", 1, 1, 1,
                dev.mela.engine.model.MediaOrigin.DEVICE, dev.mela.engine.model.MediaAvailability.DEVICE_ORIGINAL,
                uri.toString(), uri.toString(), 0, 0, mimeType = "image/png")
            val exporter = GalleryExporter(context, repository)
            val prepared = exporter.prepareShare(listOf(photo))
            assertEquals("image/png", prepared.type)
            assertArrayEquals(expected, context.contentResolver.openInputStream(prepared.clipData!!.getItemAt(0).uri)!!.use { it.readBytes() })
            exporter.clearShares()
        } finally { context.contentResolver.delete(uri, null, null) }
    }

    @Test fun interruptedShareRemovesPartialFilesAndDoesNotReturnAnIntent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = (context as MelaApplication).graph.galleryRepository
        repository.refresh(false)
        val photo = repository.observeGallery().first().first { it.id == "fixture:icloud:0001" }
        val failure = object : GalleryRepository by repository {
            override suspend fun exportOriginal(mediaId: String, output: OutputStream, motion: Boolean) {
                output.write(byteArrayOf(1, 2, 3))
                throw java.io.IOException("Interrupted")
            }
        }
        val exporter = GalleryExporter(context, failure)
        exporter.clearShares()
        assertTrue(runCatching { exporter.prepareShare(listOf(photo)) }.isFailure)
        assertTrue(File(context.cacheDir, "shares").listFiles().orEmpty().isEmpty())
    }
}
