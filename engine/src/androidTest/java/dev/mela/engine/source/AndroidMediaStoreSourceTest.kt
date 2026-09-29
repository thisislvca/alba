package dev.mela.engine.source

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mela.engine.model.BackupScope
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMediaStoreSourceTest {
    @Test
    fun readTrashStateIncludesOwnedTrashedRows() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "mela-trash-observation-${System.nanoTime()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            },
        ))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { output ->
                Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
            // Publish after writing so the close-triggered scan cannot race the trash rename.
            assertEquals(1, resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null))
            val source = AndroidMediaStoreSource(context)
            assertEquals(false, source.readTrashState(uri.toString())?.isTrashed)
            assertEquals(1, resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_TRASHED, 1)
            }, null, null))
            assertEquals(true, source.readTrashState(uri.toString())?.isTrashed)
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun discoveryOnlyIncludesCameraJpegsAddedAfterEnrollment() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_MEDIA_LOCATION)
        val resolver = context.contentResolver
        val inserted = mutableListOf<android.net.Uri>()
        fun insert(name: String, directory: String): android.net.Uri {
            val uri = requireNotNull(resolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, directory)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ))
            inserted += uri
            requireNotNull(resolver.openOutputStream(uri)).use { output ->
                Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        }
        try {
            val old = insert("mela-old-camera.jpg", "DCIM/Camera/")
            val enrolledAt = System.currentTimeMillis()
            val baseline = AndroidMediaStoreSource(context).captureEnrollmentBaseline()
            insert("mela-new-screenshot.jpg", "Pictures/Screenshots/")
            insert("mela-new-camera.jpg", "DCIM/Camera/")
            // An edit after enrollment must not make an older original eligible.
            resolver.update(old, ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "mela-edited-old-camera.jpg") }, null, null)
            val page = AndroidMediaStoreSource(context).discoverEligibleJpegs(null, 200, BackupScope.CAMERA_JPEGS, enrolledAt, baseline)
            assertEquals(listOf("mela-new-camera.jpg"), page.items.map { it.fileName })
            assertEquals(MediaStore.VOLUME_EXTERNAL_PRIMARY, page.items.single().volumeName)
        } finally {
            inserted.forEach { resolver.delete(it, null, null) }
        }
    }

    @Test
    fun galleryRevisionCanStageTheSameRealMediaStoreJpeg() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val bytes = ByteArrayOutputStream().also {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        val uri = requireNotNull(resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "mela-revision-regression.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            },
        ))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            val source = AndroidMediaStoreSource(context)
            val item = source.scanImages().single { it.fileName == "mela-revision-regression.jpg" }
            assertTrue(requireNotNull(item.addedAtEpochMillis) > 0)
            assertEquals("image/jpeg", item.mimeType)
            val output = ByteArrayOutputStream()
            val evidence = source.copyMediaStoreItem(item.contentUri, item.sourceRevision, output)
            assertEquals(item.sourceRevision, evidence.sourceRevision)
            assertTrue(bytes.contentEquals(output.toByteArray()))
        } finally {
            resolver.delete(uri, null, null)
        }
    }
}
