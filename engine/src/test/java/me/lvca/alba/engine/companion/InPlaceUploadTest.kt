package me.lvca.alba.engine.companion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.engine.cache.UploadStagingStore
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.model.AutomaticBackupPolicy
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.TransferViewState
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A MediaStore original is uploaded from where it is, hashed as it is read, with no private copy. An original edited
 * after it was queued, or while it was being sent, never reaches iCloud in that state: the upload stops before its last
 * bytes, and the item is read again and uploads its new version.
 */
@RunWith(AndroidJUnit4::class)
class InPlaceUploadTest {
    private lateinit var context: Context
    private lateinit var database: AlbaDatabase
    private lateinit var staging: UploadStagingStore
    private val phone by lazy { TestPhone(context).apply { readsInPlace = true } }
    private val icloud = TestICloud()
    private var clock = NOW
    private val stagingDirectory get() = File(context.filesDir, "upload_staging")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlbaDatabase::class.java).allowMainThreadQueries().build()
        staging = UploadStagingStore(context).also { it.sweepOrphans(emptySet(), minimumAgeMillis = 0) }
    }

    @After
    fun tearDown() {
        database.close()
        staging.sweepOrphans(emptySet(), minimumAgeMillis = 0)
    }

    @Test
    fun aMediaStoreOriginalUploadsFromWhereItIsWithoutAPrivateCopy() = runBlocking {
        val item = phone.add(1)
        val photos = enrolled()

        photos.syncNow()

        assertEquals(TransferViewState.VERIFIED, photos.observeTransfers().first().single().state)
        assertEquals("Nothing was copied into private storage", emptyList<String>(), stagingDirectory.list().orEmpty().toList())
        assertEquals("Read once to queue it and once as it was sent", 2, phone.inPlaceOpens[item.contentUri])
        assertArrayEquals(phone.bytes[item.contentUri], icloud.stored.values.single())
        assertEquals(0, icloud.downloads)
    }

    @Test
    fun anOriginalEditedWhileItIsSentStopsBeforeItsLastBytesAndItsNewVersionUploads() = runBlocking {
        val item = phone.add(1)
        val original = phone.bytes.getValue(item.contentUri)
        val edited = TestPhone.jpeg(id = 9, size = 11)
        // The second open is the upload: the photo is edited while its bytes are being read for the request.
        phone.onOpenedInPlace = { uri, opens -> if (opens == 2) phone.edit(uri, edited) }
        val photos = enrolled()

        photos.syncNow()

        assertEquals("The first version was never registered", 0, icloud.registrations)
        assertEquals("Its upload was let go as never registered", "SOURCE_CHANGED", uncertaintyReasons().single())
        assertEquals(TransferViewState.WAITING, photos.observeTransfers().first().single().state)

        photos.syncNow()

        assertEquals(TransferViewState.VERIFIED, photos.observeTransfers().first().single().state)
        assertEquals(1, icloud.registrations)
        assertArrayEquals("iCloud has the new version", edited, icloud.stored.values.single())
        assertTrue(icloud.stored.values.none { it.contentEquals(original) })
    }

    @Test
    fun anOriginalEditedAfterItWasQueuedIsReadAgainBeforeItUploads() = runBlocking {
        (1L..4L).forEach { phone.add(it) }
        val photos = enrolled()
        photos.syncNow()
        // The fourth was queued while the first three uploaded; it is edited before its turn.
        val fourth = phone.items.last()
        assertEquals(TransferViewState.WAITING, photos.observeTransfers().first().single { it.displayName == fourth.fileName }.state)
        val edited = TestPhone.jpeg(id = 40, size = 9)
        phone.edit(fourth.contentUri, edited)

        repeat(2) { photos.syncNow() }

        assertTrue(photos.observeTransfers().first().all { it.state == TransferViewState.VERIFIED })
        assertEquals(4, icloud.registrations)
        assertTrue("The edited version is what reached iCloud", icloud.stored.values.any { it.contentEquals(edited) })
    }

    private fun uncertaintyReasons(): List<String> = database.query("SELECT reason FROM upload_uncertainties", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    private suspend fun enrolled(): DefaultPhotoCompanion {
        val photos = DefaultPhotoCompanion(
            database = database,
            originals = phone,
            staging = staging,
            protocol = icloud,
            scheduler = NoWakeups,
            now = { clock },
            automaticBackupPolicy = AutomaticBackupPolicy { true },
            elapsedRealtime = { clock },
        )
        val draft = photos.beginBackupEnrollment(BackupScope.CAMERA_MEDIA)
        photos.acceptBackupDisclosure(draft.id)
        photos.finishBackupEnrollment(draft.id, includeExisting = false)
        return photos
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
