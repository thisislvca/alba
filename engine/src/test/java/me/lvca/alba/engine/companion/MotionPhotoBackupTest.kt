package me.lvca.alba.engine.companion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.engine.cache.UploadStagingStore
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.CatalogItemEntity
import me.lvca.alba.engine.database.MotionPhotoPartEntity
import me.lvca.alba.engine.model.AutomaticBackupPolicy
import me.lvca.alba.engine.model.BackupAttribute
import me.lvca.alba.engine.model.BackupBreadcrumb
import me.lvca.alba.engine.model.BackupDiagnostics
import me.lvca.alba.engine.model.BackupEvent
import me.lvca.alba.engine.model.BackupPhase
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.BackupSettings
import me.lvca.alba.engine.model.BackupTelemetry
import me.lvca.alba.engine.model.BackupTrace
import me.lvca.alba.engine.model.DiagnosticSeverity
import me.lvca.alba.engine.model.MotionPhotoMode
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.TransferFailureReason
import me.lvca.alba.engine.model.TransferViewState
import me.lvca.alba.engine.model.UploadReceipt
import me.lvca.alba.engine.source.DeviceMediaRecord
import me.lvca.alba.engine.source.MotionPhotoFixtures
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Motion photos backed up as Live Photos: the still uploads as before, then its video is read out of the phone's file
 * and attached. "Backed up" needs both the still's receipt and a re-read showing the attached video; a crash mid-attach
 * resumes without sending anything twice; a refused attach leaves the still backed up and flagged, with Retry; and Free
 * up space removes a motion photo only once both its still and its video pass the byte check.
 */
@RunWith(AndroidJUnit4::class)
class MotionPhotoBackupTest {
    private lateinit var context: Context
    private lateinit var database: AlbaDatabase
    private lateinit var staging: UploadStagingStore
    private val phone by lazy { TestPhone(context) }
    private val icloud = TestICloud()
    private val recorder = RecordingDiagnostics()
    private var clock = NOW
    private var settings = BackupSettings() // The default: As Live Photo.
    private val policy = object : AutomaticBackupPolicy {
        override fun isUnmetered() = true
        override fun settings() = settings
    }
    private val video = MotionPhotoFixtures.mp4(durationMs = 2_966, width = 1440, height = 1080)

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
    fun aMotionPhotoBecomesALivePhotoWithItsOwnVideoInNanoseconds() = runBlocking {
        val item = phone.add(1, "PXL_20261101_101010123.MP.jpg", MotionPhotoFixtures.pixelMotionPhoto(video, timestampUs = 1_483_000))
        val photos = enrolled()

        photos.syncNow()

        assertEquals("The still uploads byte for byte, video kept inside", phone.bytes[item.contentUri]!!.toList(),
            icloud.stored.values.single().toList())
        val attached = icloud.attaches.single()
        assertArrayEquals("The video attached is exactly the MP4 inside the file", video, attached.bytes)
        assertEquals(2_966_000_000L, attached.durationNanos)
        assertEquals(1_483_000_000L, attached.stillTimeNanos)
        assertEquals(1080 to 1440, attached.width to attached.height)
        assertTrue(attached.contentIdentifier.matches(Regex("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}")))
        assertEquals(MotionPhotoPartEntity.ATTACHED, part().state)
        assertEquals(TransferViewState.VERIFIED, photos.observeTransfers().first().single().state)
        assertEquals(1, photos.backup.backupProgress().backedUp)
    }

    @Test
    fun backedUpNeedsTheStillsReceiptAndARereadShowingTheVideo() = runBlocking {
        phone.add(1, "PXL.MP.jpg", MotionPhotoFixtures.pixelMotionPhoto(video))
        val photos = enrolled()
        // iCloud took the attach, but a fresh read of the master does not show the video yet.
        icloud.liveRecordAs = { null }

        photos.syncNow()

        assertNotNull("The still alone is verified by its receipt", database.verificationDao().findCurrentProof("device:external:1", TestICloud.ACCOUNT))
        assertEquals(MotionPhotoPartEntity.ATTACHING, part().state)
        val view = photos.observeTransfers().first().single()
        assertEquals("Not backed up as a Live Photo until the video is read back", TransferViewState.UPLOADING, view.state)
        assertEquals(StoredMessage.ADDING_THE_LIVE_PHOTO_MOTION.code, view.message)
        assertEquals(0, photos.backup.backupProgress().backedUp)

        icloud.liveRecordAs = { receipt -> receipt?.let { UploadReceipt(it.byteCount, "another checksum") } }
        clock += Reconciler.RETRY_BACKOFF_CAP_MILLIS
        photos.syncNow()
        assertEquals("A video with another checksum is not this one", MotionPhotoPartEntity.ATTACHING, part().state)

        icloud.liveRecordAs = { it }
        clock += Reconciler.RETRY_BACKOFF_CAP_MILLIS
        photos.syncNow()
        assertEquals(MotionPhotoPartEntity.ATTACHED, part().state)
        assertEquals(TransferViewState.VERIFIED, photos.observeTransfers().first().single().state)
        assertEquals(1, photos.backup.backupProgress().backedUp)
        assertEquals("The attach was sent once; later passes only read it back", 1, icloud.liveModifies)
    }

    @Test
    fun aCrashMidAttachResumesFromItsJournalWithoutSendingTheVideoAgain() = runBlocking {
        phone.add(1, "PXL.MP.jpg", MotionPhotoFixtures.pixelMotionPhoto(video))
        icloud.liveCrashesAfterUpload = 1
        enrolled().syncNow()
        assertEquals(MotionPhotoPartEntity.ATTACHING, part().state)
        assertEquals(1, part().tries)
        assertEquals(BackupBreadcrumb.LIVE_ATTACH_RETRY, recorder.breadcrumbs.single().first)

        // The process starts again; the attach is due after its backoff.
        clock += Reconciler.retryBackoffMillis(0)
        companion().syncNow()

        assertEquals(MotionPhotoPartEntity.ATTACHED, part().state)
        assertEquals("The video was uploaded once", 1, icloud.liveUploads)
        assertEquals(1, icloud.liveModifies)
        assertEquals("Both tries used the same journal row", 1, icloud.livePhases.size)
    }

    @Test
    fun aRefusedAttachLeavesTheStillBackedUpFlaggedWithRetry() = runBlocking {
        phone.add(1, "PXL.MP.jpg", MotionPhotoFixtures.pixelMotionPhoto(video))
        icloud.refuseLive = "BAD_REQUEST"
        val photos = enrolled()

        photos.syncNow()

        assertEquals(MotionPhotoPartEntity.FAILED, part().state)
        assertNotNull("The still stays backed up", database.verificationDao().findCurrentProof("device:external:1", TestICloud.ACCOUNT))
        val view = photos.observeTransfers().first().single()
        assertEquals(TransferViewState.NEEDS_ATTENTION, view.state)
        assertEquals(TransferFailureReason.LIVE_PHOTO_NOT_ATTACHED, view.failureReason)
        assertEquals(StoredMessage.LIVE_PHOTO_PART_NOT_ATTACHED.code, view.message)
        assertTrue(view.retryable)
        assertEquals("A failed attach still counts the photo itself as backed up", 1, photos.backup.backupProgress().backedUp)

        icloud.refuseLive = null
        photos.backup.retryTransfer(view.id)
        photos.syncNow()

        assertEquals(MotionPhotoPartEntity.ATTACHED, part().state)
        assertEquals("The still was not uploaded again", 1, icloud.registrations)
        assertEquals(TransferViewState.VERIFIED, photos.observeTransfers().first().single().state)
    }

    @Test
    fun aStillICloudAlreadyHadIsNeverGivenAVideo() = runBlocking {
        phone.add(1, "PXL.MP.jpg", MotionPhotoFixtures.pixelMotionPhoto(video))
        val photos = enrolled()
        photos.syncNow()
        // Had the registration been a duplicate of an item iCloud already had, no video may be attached to it.
        val attemptId = database.transferDao().findTransfer(part().transferId)!!.activeAttemptId!!
        database.openHelper.writableDatabase.execSQL("UPDATE upload_acceptances SET duplicateHint = 1 WHERE attemptId = '$attemptId'")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE motion_photo_parts SET state = 'ATTACHING', receiptFileChecksum = NULL, attachedAtEpochMillis = NULL",
        )
        icloud.attaches.clear()

        photos.syncNow()

        assertEquals(0, icloud.attaches.size)
        assertEquals(MotionPhotoPartEntity.FAILED, part().state)
    }

    @Test
    fun freeUpSpaceRemovesAMotionPhotoOnlyOnceItsStillAndItsVideoPassTheByteCheck() = runBlocking {
        val motion = phone.add(1, "PXL.MP.jpg", MotionPhotoFixtures.pixelMotionPhoto(video))
        val plain = phone.add(2, "IMG_2.jpg", MotionPhotoFixtures.plainPhoto())
        icloud.liveRecordAs = { null }
        val photos = enrolled()
        repeat(2) { photos.syncNow() }
        listOf(motion, plain).forEach { database.catalogDao().upsertItems(listOf(deviceRow(it))) }
        assertEquals(MotionPhotoPartEntity.NOT_MOTION, database.motionPhotoDao().find(transferFor(plain.id))!!.state)

        assertEquals("A motion photo whose video is not attached yet stays", listOf(plain.id), photos.backup.freeUpSpaceCandidates().mediaIds)
        val early = photos.prepareTrash(setOf(motion.id), allowPartial = true)
        assertEquals(StoredMessage.MOTION_PHOTO_VIDEO_NOT_BACKED_UP_YET.code, early.refusals[motion.id])

        icloud.liveRecordAs = { it }
        clock += Reconciler.RETRY_BACKOFF_CAP_MILLIS
        photos.syncNow()
        assertEquals(setOf(motion.id, plain.id), photos.backup.freeUpSpaceCandidates().mediaIds.toSet())

        // iCloud now serves a different video than the phone holds.
        icloud.serveLiveAs = { bytes -> bytes.copyOf().also { it[it.lastIndex]++ } }
        val differs = photos.prepareTrash(setOf(motion.id, plain.id), allowPartial = true)
        assertEquals(1, icloud.liveDownloads)
        assertEquals(setOf(plain.id), differs.eligibleMediaIds)
        assertEquals(StoredMessage.THE_CURRENT_LOCAL_AND_ICLOUD_ORIGINALS_NO_LONGER_MATCH.code, differs.refusals[motion.id])
        assertEquals(BackupEvent.BYTE_CHECK_FAILED, recorder.events.last().first)
        assertEquals(DiagnosticSeverity.FATAL, recorder.events.last().second)

        icloud.serveLiveAs = { it }
        val both = photos.prepareTrash(setOf(motion.id), allowPartial = true)
        assertEquals("Still and video both downloaded and compared", setOf(motion.id), both.eligibleMediaIds)
        assertEquals(2, icloud.liveDownloads)
        assertNotNull(part().byteCheckedAtEpochMillis)
    }

    @Test
    fun photoOnlyKeepsBackingUpTheStillAlone() = runBlocking {
        settings = BackupSettings(motionPhotos = MotionPhotoMode.PHOTO_ONLY)
        phone.add(1, "PXL.MP.jpg", MotionPhotoFixtures.pixelMotionPhoto(video))
        enrolled().syncNow()

        assertEquals(1, icloud.registrations)
        assertEquals(0, icloud.attaches.size)
        assertNull(database.motionPhotoDao().find(transferFor("device:external:1")))
    }

    @Test
    fun diagnosticsOfAFailedAttachCarryNothingThatNamesThePhotoOrAccount() = runBlocking {
        val name = "PXL_20261101_101010123.MP.jpg"
        phone.add(1, name, MotionPhotoFixtures.pixelMotionPhoto(video))
        icloud.refuseLive = "BAD_REQUEST"
        enrolled().syncNow()

        val failure = recorder.events.single { it.first == BackupEvent.LIVE_ATTACH_FAILED }
        assertEquals(DiagnosticSeverity.ERROR, failure.second)
        val sent = BackupTelemetry.sanitize(failure.third)
        assertEquals("BAD_REQUEST", sent["error"])
        assertEquals("motion_photo_v1", sent["motion_format"])

        val secrets = listOf(name, TestICloud.ACCOUNT, "master:", "asset:", sha256(video), phone.items.single().contentUri) +
            icloud.registered.map { it.master.name } + icloud.registered.mapNotNull { it.originalFileChecksum }
        val everything = recorder.events.map { BackupTelemetry.sanitize(it.third) } +
            recorder.spans.map { BackupTelemetry.sanitize(it.second) } +
            recorder.breadcrumbs.map { BackupTelemetry.sanitize(it.second) }
        assertTrue(recorder.spans.any { it.first == BackupPhase.LIVE_ATTACH })
        everything.flatMap { it.entries }.forEach { (key, value) ->
            val text = value.toString()
            assertTrue("$key is not an allowed attribute", key in BackupAttribute.entries.map { it.id })
            assertFalse("$key=$text looks like a path or URL", text.contains('/') || text.contains('\\') || text.contains(':'))
            val base64Like = !text.endsWith("Exception") && text.length >= 24 && text.matches(Regex("[A-Za-z0-9+/=_-]+"))
            assertFalse("$key=$text looks like base64 or a checksum", base64Like)
            assertFalse("$key=$text looks like a file name", text.matches(Regex(".+\\.[A-Za-z0-9]{2,4}")))
            secrets.forEach { secret -> assertFalse("$key carries $secret", text.contains(secret)) }
        }
        // Anything not on the allowed list is dropped before it could be sent.
        assertEquals(
            emptyMap<String, Any>(),
            BackupTelemetry.sanitize(mapOf(BackupAttribute.ERROR to name, BackupAttribute.PHASE to "https://p01.icloud.com/x",
                BackupAttribute.OUTCOME to "c2VjcmV0LXJlY29yZC1uYW1lLWJhc2U2NA==")),
        )
    }

    private suspend fun part() = database.motionPhotoDao().find(transferFor("device:external:1"))!!

    private suspend fun transferFor(mediaId: String) =
        database.transferDao().findTransfersForMedia(TestICloud.ACCOUNT, mediaId).single().transferId

    private fun companion() = DefaultPhotoCompanion(
        database = database,
        originals = phone,
        staging = staging,
        protocol = icloud,
        scheduler = NoWakeups,
        now = { clock },
        automaticBackupPolicy = policy,
        elapsedRealtime = { clock },
        diagnostics = recorder,
    )

    private suspend fun enrolled(): DefaultPhotoCompanion {
        val photos = companion()
        val draft = photos.beginBackupEnrollment(BackupScope.CAMERA_MEDIA)
        photos.acceptBackupDisclosure(draft.id)
        photos.finishBackupEnrollment(draft.id, includeExisting = false)
        return photos
    }

    private fun deviceRow(record: DeviceMediaRecord) = CatalogItemEntity(
        mediaId = record.id, fileName = record.fileName, capturedAtEpochMillis = record.capturedAtEpochMillis,
        width = 10, height = 10, origin = "DEVICE", sourceRevision = record.sourceRevision, localUri = record.contentUri,
        previewCachePath = null, originalCachePath = null, accentStartArgb = 0, accentEndArgb = 0, lastSeenScanId = "scan",
    )

    /** Records what backup reports, as the app's Sentry layer would receive it. */
    private class RecordingDiagnostics : BackupDiagnostics {
        val spans = mutableListOf<Pair<BackupPhase, Map<BackupAttribute, Any>>>()
        val breadcrumbs = mutableListOf<Pair<BackupBreadcrumb, Map<BackupAttribute, Any>>>()
        val events = mutableListOf<Triple<BackupEvent, DiagnosticSeverity, Map<BackupAttribute, Any>>>()

        override fun start(phase: BackupPhase, attributes: Map<BackupAttribute, Any>) = BackupTrace { outcome, more ->
            spans += phase to (attributes + more + (BackupAttribute.OUTCOME to outcome))
        }

        override fun breadcrumb(crumb: BackupBreadcrumb, attributes: Map<BackupAttribute, Any>) {
            breadcrumbs += crumb to attributes
        }

        override fun capture(event: BackupEvent, severity: DiagnosticSeverity, attributes: Map<BackupAttribute, Any>) {
            events += Triple(event, severity, attributes)
        }
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
