package me.lvca.alba.app.gallery

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.testing.MediaStoreFixtures
import me.lvca.alba.app.testing.grantTestMediaPermissions
import me.lvca.alba.engine.source.AndroidMediaStoreSource
import org.junit.Assert.*
import org.junit.Test

class DeviceScanTest {

    @Test fun deviceScanIncludesVideosFoldersAndRecoverableTrash() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        grantTestMediaPermissions(context)
        val resolver = context.contentResolver
        val uri = MediaStoreFixtures.insertAsset(context.assets, "fixture_video.mp4", "alba-table-stakes-test.mp4", "video/mp4", "Movies/AlbaTest")
        try {
            val source = AndroidMediaStoreSource(context)
            val video = source.scanImages().single { it.contentUri == uri.toString() }
            assertEquals("video/mp4", video.mimeType)
            assertEquals("AlbaTest", video.deviceFolderName)
            assertTrue(video.id.startsWith("device:video:"))
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
            assertTrue(source.scanImages().single { it.id == video.id }.isTrashed)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }, null, null)
            assertFalse(source.scanImages().single { it.id == video.id }.isTrashed)
        } finally { resolver.delete(uri, null, null) }
    }
}
