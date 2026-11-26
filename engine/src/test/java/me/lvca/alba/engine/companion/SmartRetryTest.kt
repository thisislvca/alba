package me.lvca.alba.engine.companion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.lvca.alba.engine.cache.UploadStagingStore
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.TransferState
import me.lvca.alba.engine.model.AutomaticBackupPolicy
import me.lvca.alba.engine.model.BackupNetwork
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.BackupSettings
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.TransferFailureReason
import me.lvca.alba.engine.model.WakeReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Large automatic items upload only in a long run on an unmetered network, and clear permanent errors stop retries with
 * their reason: a file iCloud refuses, and a file no longer on the phone.
 */
@RunWith(AndroidJUnit4::class)
class SmartRetryTest {
    private lateinit var context: Context
    private lateinit var database: AlbaDatabase
    private lateinit var staging: UploadStagingStore
    private val phone by lazy { TestPhone(context) }
    private val icloud = TestICloud()
    private var clock = NOW
    private var network = BackupNetwork.UNMETERED

    /** Photos may use mobile data, so only the size of the large item holds it back there. */
    private val policy = object : AutomaticBackupPolicy {
        override fun isUnmetered() = network == BackupNetwork.UNMETERED
        override fun network() = network
        override fun settings() = BackupSettings(photosOnMobileData = true, videosOnMobileData = true)
    }

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
    fun aLargeItemWaitsForALongRunOnAnUnmeteredNetwork() = runBlocking {
        phone.add(1, content = TestPhone.jpeg(1, size = LARGE + 1))
        phone.add(2)
        val photos = enrolled()

        photos.wake(WakeReason.BACKGROUND)
        assertEquals("A short background run uploads the small item only", listOf("IMG_2.jpg"), icloud.uploadedNames)
        photos.wake(WakeReason.BACKGROUND)
        assertEquals(listOf("IMG_2.jpg"), icloud.uploadedNames)

        network = BackupNetwork.METERED
        photos.wake(WakeReason.LONG_RUNNING)
        assertEquals("A long run on mobile data leaves it waiting too", listOf("IMG_2.jpg"), icloud.uploadedNames)

        network = BackupNetwork.UNMETERED
        photos.wake(WakeReason.LONG_RUNNING)

        assertEquals(listOf("IMG_2.jpg", "IMG_1.jpg"), icloud.uploadedNames)
        assertTrue(database.transferDao().observeTransfers().first().all { it.state == TransferState.VERIFIED })
    }

    @Test
    fun aLargeItemInterruptedMidUploadResumesOnlyInALongRun() = runBlocking {
        phone.add(1, content = TestPhone.jpeg(1, size = LARGE + 1))
        val photos = enrolled()
        // The process dies right after the reservation, as a run cut off at its limit would leave it.
        icloud.freezeAt += "RESERVED"
        val frozen = CompletableDeferred<Unit>()
        icloud.onFrozen = { frozen.complete(Unit) }
        // Not a child of the test: a dead process's work is never joined.
        launch(Job() + CoroutineExceptionHandler { _, _ -> }) { photos.wake(WakeReason.LONG_RUNNING) }
        withTimeout(TIMEOUT_MILLIS) { frozen.await() }

        val restarted = companion()
        restarted.wake(WakeReason.BACKGROUND)
        assertEquals("A short run does not resume it", TransferState.SENDING, state())
        restarted.wake(WakeReason.LONG_RUNNING)
        assertEquals(TransferState.VERIFIED, state())
        assertEquals(1, icloud.registrations)
    }

    @Test
    fun aFileICloudRefusesStopsForGoodWithItsReason() = runBlocking {
        phone.add(1)
        icloud.rejectStatus = 415
        val photos = enrolled()

        photos.syncNow()

        val row = photos.observeTransfers().first().single()
        assertEquals(TransferFailureReason.FORMAT_NOT_SUPPORTED, row.failureReason)
        assertEquals(StoredMessage.ICLOUD_REJECTED_THIS_FILE.code, row.message)
        assertTrue("Nothing of it is in iCloud, so the user may still retry", row.retryable)
        assertEquals(0, database.transferDao().countWriteSlots(TestICloud.ACCOUNT))
        clock += Reconciler.RETRY_BACKOFF_CAP_MILLIS * 2
        photos.syncNow()
        assertEquals("No automatic retry", 1, icloud.phases.size)
        assertEquals(0, icloud.registrations)
    }

    @Test
    fun aFileNoLongerOnThePhoneStopsForGoodWithItsReason() = runBlocking {
        phone.readsInPlace = true
        val original = phone.add(1)
        // Deleted after it was queued, while it was being sent.
        phone.onOpenedInPlace = { uri, opens ->
            if (opens == 2) {
                phone.revisions.remove(uri)
                phone.bytes.remove(uri)
                phone.items.removeAll { it.contentUri == uri }
            }
        }
        val photos = enrolled()

        photos.syncNow()
        photos.syncNow()

        val row = photos.observeTransfers().first().single()
        assertEquals(TransferState.NEEDS_ATTENTION, database.transferDao().findTransfer(row.id.value)?.state)
        assertEquals(TransferFailureReason.FILE_GONE, row.failureReason)
        assertEquals(StoredMessage.THIS_FILE_IS_NO_LONGER_ON_THE_PHONE.code, row.message)
        assertEquals(original.id, row.mediaId)
        assertEquals(0, icloud.registrations)
    }

    private suspend fun state() = database.transferDao().observeTransfers().first().single().state

    private suspend fun enrolled(): DefaultPhotoCompanion {
        val photos = companion()
        val draft = photos.beginBackupEnrollment(BackupScope.CAMERA_MEDIA)
        photos.acceptBackupDisclosure(draft.id)
        photos.finishBackupEnrollment(draft.id, includeExisting = false)
        return photos
    }

    private fun companion() = DefaultPhotoCompanion(
        database = database,
        originals = phone,
        staging = staging,
        protocol = icloud,
        scheduler = NoWakeups,
        now = { clock },
        automaticBackupPolicy = policy,
        elapsedRealtime = { clock },
        largeItemBytes = LARGE.toLong(),
    )

    private companion object {
        const val NOW = 1_000_000L
        const val TIMEOUT_MILLIS = 10_000L

        /** A stand-in for 100 MB, so the test needs no large files. */
        const val LARGE = 32
    }
}
