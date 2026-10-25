package me.lvca.alba.engine.source

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.lvca.alba.engine.model.BackupScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

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
                put(MediaStore.Images.Media.DISPLAY_NAME, "alba-trash-observation-${System.nanoTime()}.jpg")
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
    fun scanIncludesVideosWithTheirFolderAndRecoverableTrash() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val context = ApplicationProvider.getApplicationContext<Context>()
        grantImageAccess(context)
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_VIDEO)
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "alba-scan-video-${System.nanoTime()}.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/AlbaTest")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { output ->
                InstrumentationRegistry.getInstrumentation().context.assets.open("upload-formats/avc.mp4").use { it.copyTo(output) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val source = AndroidMediaStoreSource(context)
            val video = source.scanImages().single { it.contentUri == uri.toString() }
            assertEquals("video/mp4", video.mimeType)
            assertEquals("AlbaTest", video.deviceFolderName)
            assertTrue(video.id.startsWith("device:video:"))
            // Trashed rows stay in the scan, marked, so they can be shown in Phone trash and restored.
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
            assertTrue(source.scanImages().single { it.id == video.id }.isTrashed)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }, null, null)
            assertFalse(source.scanImages().single { it.id == video.id }.isTrashed)
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun discoveryOnlyIncludesCameraJpegsAddedAfterEnrollment() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        grantImageAccess(context)
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_MEDIA_LOCATION)
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
            val old = insert("alba-old-camera.jpg", "DCIM/Camera/")
            val enrolledAt = System.currentTimeMillis()
            val baseline = AndroidMediaStoreSource(context).captureEnrollmentBaseline()
            insert("alba-new-screenshot.jpg", "Pictures/Screenshots/")
            insert("alba-new-camera.jpg", "DCIM/Camera/")
            // An edit after enrollment must not make an older original eligible.
            resolver.update(old, ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "alba-edited-old-camera.jpg") }, null, null)
            val page = AndroidMediaStoreSource(context).discoverEligibleJpegs(null, 200, BackupScope.CAMERA_JPEGS, enrolledAt, baseline)
            assertEquals(listOf("alba-new-camera.jpg"), page.items.map { it.fileName })
            assertEquals(MediaStore.VOLUME_EXTERNAL_PRIMARY, page.items.single().volumeName)
        } finally {
            inserted.forEach { resolver.delete(it, null, null) }
        }
    }

    @Test
    fun galleryRevisionCanStageTheSameRealMediaStoreJpeg() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val context = ApplicationProvider.getApplicationContext<Context>()
        grantImageAccess(context)
        val resolver = context.contentResolver
        val bytes = ByteArrayOutputStream().also {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        val uri = requireNotNull(resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "alba-revision-regression.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            },
        ))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            val source = AndroidMediaStoreSource(context)
            val item = source.scanImages().single { it.fileName == "alba-revision-regression.jpg" }
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

    /** Each test grants what it reads: the orchestrator clears the app's data, and with it runtime grants, between tests. */
    private fun grantImageAccess(context: Context) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}
