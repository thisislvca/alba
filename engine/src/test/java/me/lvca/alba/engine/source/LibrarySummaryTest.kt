package me.lvca.alba.engine.source

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import androidx.core.content.contentValuesOf
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.lvca.alba.engine.model.BackupLibrarySummary
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.DeviceFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * The setup's "Back up all N photos and videos (X GB)" counts what backup covers from MediaStore: photos and videos in
 * the camera folder and the chosen folders, with their sizes, and nothing before Alba can read the whole library.
 * MediaStore is played by SQLite here, so the selection backup builds is the one that runs.
 */
@RunWith(AndroidJUnit4::class)
class LibrarySummaryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val whatsApp = BackupFolders.folderId("external_primary", "whatsapp")

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
    }

    @Test
    fun nothingIsCountedBeforeAlbaCanReadEveryPhotoAndVideo() = runBlocking {
        assertNull(summary(setOf(DeviceFolder.SCREENSHOTS)))
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertNull("Only some photos allowed", summary(setOf(DeviceFolder.SCREENSHOTS)))
    }

    @Test
    fun countsThePhotosAndVideosBackupCoversWithTheirSizes() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)

        // The camera's photo, video and sizeless photo, and the screenshots new enrollments start with.
        assertEquals(BackupLibrarySummary(items = 4, bytes = 6_200L), summary(setOf(DeviceFolder.SCREENSHOTS)))
        // A folder the user added counts too; another folder and a non-media file never do.
        assertEquals(BackupLibrarySummary(items = 5, bytes = 6_500L), summary(setOf(DeviceFolder.SCREENSHOTS, whatsApp)))
        assertEquals(BackupLibrarySummary(items = 3, bytes = 6_000L), summary(emptySet()))
    }

    private suspend fun summary(folders: Set<String>) = AndroidMediaStoreSource(app).librarySummary(BackupScope.CURRENT, folders)

    /** MediaStore's files table, with the columns backup's selection reads. */
    class FakeMediaStore : ContentProvider() {
        private lateinit var db: SQLiteDatabase

        override fun onCreate(): Boolean {
            db = SQLiteDatabase.create(null)
            db.execSQL(
                "CREATE TABLE files (_id INTEGER PRIMARY KEY, media_type INTEGER, mime_type TEXT, relative_path TEXT, " +
                    "volume_name TEXT, bucket_id TEXT, _size INTEGER, date_added INTEGER, generation_added INTEGER)",
            )
            listOf(
                row(IMAGE, "image/jpeg", "DCIM/Camera/", "camera", 1_000L),
                row(VIDEO, "video/mp4", "DCIM/Camera/", "camera", 5_000L),
                row(IMAGE, "image/heic", "DCIM/Camera/", "camera", null),
                row(IMAGE, "image/png", "Pictures/Screenshots/", "screenshots", 200L),
                row(IMAGE, "image/jpeg", "Pictures/WhatsApp/", "whatsapp", 300L),
                row(IMAGE, "image/jpeg", "Pictures/Instagram/", "instagram", 700L),
                row(NONE, "application/pdf", "DCIM/Camera/", "camera", 9_000L),
            ).forEach { db.insert("files", null, it) }
            return true
        }

        override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor =
            db.query("files", projection, queryArgs?.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
                queryArgs?.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS), null, null, null)

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
            db.query("files", projection, selection, selectionArgs, null, null, sortOrder)

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

        private fun row(type: Int, mime: String, path: String, bucket: String, size: Long?) = contentValuesOf(
            "media_type" to type, "mime_type" to mime, "relative_path" to path, "volume_name" to "external_primary",
            "bucket_id" to bucket, "_size" to size, "date_added" to 1L, "generation_added" to 1L,
        )

        private companion object {
            const val NONE = MediaStore.Files.FileColumns.MEDIA_TYPE_NONE
            const val IMAGE = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
            const val VIDEO = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        }
    }
}
