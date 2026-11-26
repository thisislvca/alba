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
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.TransferViewState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Up to three uploads per account are in flight at once, while discovery queues the items after them. Each attempt
 * keeps its own journal row and write slot, so a process death with three in flight recovers each on its own, at
 * whatever phase it stopped, and nothing is registered twice.
 */
@RunWith(AndroidJUnit4::class)
class ParallelUploadTest {
    private lateinit var context: Context
    private lateinit var database: AlbaDatabase
    private lateinit var staging: UploadStagingStore
    private val phone by lazy { TestPhone(context) }
    private val icloud = TestICloud()
    private var clock = NOW

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
    fun threeUploadsAreInFlightAtOnceWhileDiscoveryQueuesTheNext() = runBlocking {
        (1L..4L).forEach { phone.add(it) }
        val photos = enrolled(companion())
        // Each upload waits until three are in flight together; uploads one at a time would never get there.
        val threeInFlight = CompletableDeferred<Unit>()
        icloud.hold = threeInFlight
        icloud.onInFlight = { if (it == MAX_UPLOADS_IN_FLIGHT) threeInFlight.complete(Unit) }

        withTimeout(TIMEOUT_MILLIS) { photos.syncNow() }

        assertEquals(MAX_UPLOADS_IN_FLIGHT, icloud.maxInFlight)
        assertEquals(3, icloud.registrations)
        val rows = photos.observeTransfers().first()
        assertEquals(3, rows.count { it.state == TransferViewState.VERIFIED })
        assertEquals("The fourth was queued while the others uploaded", 1, rows.count { it.state == TransferViewState.WAITING })
        assertEquals("Every verified upload released its write slot", 0, database.transferDao().countWriteSlots(TestICloud.ACCOUNT))

        withTimeout(TIMEOUT_MILLIS) { photos.syncNow() }

        assertTrue(photos.observeTransfers().first().all { it.state == TransferViewState.VERIFIED })
        assertEquals(4, icloud.registrations)
        assertEquals("Each verified by its receipt", 0, icloud.downloads)
    }

    @Test
    fun aCrashWithThreeUploadsInFlightRecoversEachFromItsOwnJournal() = runBlocking {
        (1L..3L).forEach { phone.add(it) }
        // The process dies with one upload reserved, one whose bytes were received, and one sending its bytes.
        icloud.freezeAt += listOf("RESERVED", "RECEIPT_SAVED", "BYTES_SENDING")
        val allFrozen = CompletableDeferred<Unit>()
        icloud.onFrozen = { if (it == 3) allFrozen.complete(Unit) }
        val dying = enrolled(companion())
        // Not a child of the test: a dead process's work is never joined.
        launch(Job() + CoroutineExceptionHandler { _, _ -> }) { dying.syncNow() }
        withTimeout(TIMEOUT_MILLIS) { allFrozen.await() }
        assertEquals(3, database.transferDao().countWriteSlots(TestICloud.ACCOUNT))
        assertEquals(3, database.transferDao().findInterruptedSending().size)
        assertEquals(0, icloud.registrations)

        // The app starts again on the same journal.
        val restarted = companion()
        repeat(3) { withTimeout(TIMEOUT_MILLIS) { restarted.syncNow() } }
        // The upload stopped mid-bytes was never registered: it starts a fresh attempt after its backoff.
        clock += Reconciler.retryBackoffMillis(0) + 1
        repeat(2) { withTimeout(TIMEOUT_MILLIS) { restarted.syncNow() } }

        val transfers = database.transferDao().observeTransfers().first()
        assertTrue(transfers.joinToString { "${it.displayName}=${it.state}" }, transfers.all { it.state == TransferState.VERIFIED })
        assertEquals("Each photo is registered exactly once", listOf("IMG_1.jpg", "IMG_2.jpg", "IMG_3.jpg"), icloud.uploadedNames.sorted())
        assertEquals(0, database.transferDao().countWriteSlots(TestICloud.ACCOUNT))
    }

    private suspend fun enrolled(photos: DefaultPhotoCompanion): DefaultPhotoCompanion {
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
        automaticBackupPolicy = AutomaticBackupPolicy { true },
        elapsedRealtime = { clock },
    )

    private companion object {
        const val NOW = 1_000_000L
        const val TIMEOUT_MILLIS = 10_000L
    }
}
