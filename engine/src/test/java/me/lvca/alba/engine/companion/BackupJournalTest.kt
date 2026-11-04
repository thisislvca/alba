package me.lvca.alba.engine.companion

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.engine.cache.UploadStagingStore
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.CatalogItemEntity
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.AutomaticBackupPolicy
import me.lvca.alba.engine.model.BackupChange
import me.lvca.alba.engine.model.BackupNetwork
import me.lvca.alba.engine.model.BackupProgress
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.BackupSettings
import me.lvca.alba.engine.model.CandidatePage
import me.lvca.alba.engine.model.ICloudDestination
import me.lvca.alba.engine.model.ICloudPhotoProtocol
import me.lvca.alba.engine.model.ICloudStorageFullException
import me.lvca.alba.engine.model.OneShotUploadSource
import me.lvca.alba.engine.model.PhoneMetadata
import me.lvca.alba.engine.model.PhotoCompanionException
import me.lvca.alba.engine.model.PreparedICloudUpload
import me.lvca.alba.engine.model.ProtocolArea
import me.lvca.alba.engine.model.ProtocolPause
import me.lvca.alba.engine.model.ProtocolStatusGate
import me.lvca.alba.engine.model.RemoteOriginalObservation
import me.lvca.alba.engine.model.RemotePairCandidate
import me.lvca.alba.engine.model.RemoteRecordRef
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.TransferViewState
import me.lvca.alba.engine.model.UploadAcceptance
import me.lvca.alba.engine.model.UserSelectedPhoto
import me.lvca.alba.engine.model.WakeReason
import me.lvca.alba.engine.model.WakeupScheduler
import me.lvca.alba.engine.source.DeviceMediaRecord
import me.lvca.alba.engine.source.ExactOriginalEvidence
import me.lvca.alba.engine.source.ExactOriginalSource
import me.lvca.alba.engine.source.ExistingMedia
import me.lvca.alba.engine.source.ExistingMediaCursor
import me.lvca.alba.engine.source.ExistingMediaPage
import me.lvca.alba.engine.source.LocalDiscoveryCheckpoint
import me.lvca.alba.engine.source.LocalDiscoveryPage
import me.lvca.alba.engine.source.LocalTrashState
import me.lvca.alba.engine.source.UnsupportedUploadException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.OutputStream
import java.security.MessageDigest

/** Automatic backup coverage, skipped items, retry, network settings and iCloud storage full, on an in-memory journal. */
@RunWith(AndroidJUnit4::class)
class BackupJournalTest {
    private lateinit var context: Context
    private lateinit var database: AlbaDatabase
    private lateinit var staging: UploadStagingStore
    private val source by lazy { FakeSource(context) }
    private val protocol = FakeProtocol()
    private var clock = NOW
    private var elapsed = 5_000_000L
    private var network = BackupNetwork.UNMETERED
    private var settings = BackupSettings()
    private val remoteStatus = SwitchableStatus()
    private var charging = true

    /** When set, the network turns metered on the check after this many more. */
    private var meteredAfterChecks: Int? = null
    private val policy = object : AutomaticBackupPolicy {
        override fun isUnmetered() = network() == BackupNetwork.UNMETERED
        override fun network(): BackupNetwork {
            meteredAfterChecks?.let { left ->
                if (left == 0) { network = BackupNetwork.METERED; meteredAfterChecks = null } else meteredAfterChecks = left - 1
            }
            return network
        }
        override fun settings() = settings
        override fun isCharging() = charging
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
    fun refusedFormatsAreReportedAsSkippedAndDoNotHoldUpLaterItems() = runBlocking {
        source.items += item(1, "animated.webp", "image/webp", refuse = UnsupportedUploadException.ANIMATED)
        source.items += item(2, "clip.avif", "image/avif")
        source.items += item(3, "camera.jpg", "image/jpeg")
        val photos = enrolled()

        photos.syncNow()

        val rows = photos.observeTransfers().first().associateBy { it.displayName }
        assertEquals(TransferViewState.UNSUPPORTED, rows.getValue("animated.webp").state)
        assertEquals(StoredMessage.UPLOAD_ANIMATED_UNSUPPORTED.code, rows.getValue("animated.webp").message)
        assertEquals(StoredMessage.UPLOAD_AVIF_UNSUPPORTED.code, rows.getValue("clip.avif").message)
        assertTrue(rows.getValue("clip.avif").retryable && rows.getValue("clip.avif").automatic)
        assertEquals(TransferViewState.VERIFIED, rows.getValue("camera.jpg").state)

        photos.syncNow()
        assertEquals("A later pass must not report the same item twice", 3, photos.observeTransfers().first().size)
    }

    @Test
    fun formatsICloudWebDoesNotTakeAreSkippedWithWhyAndARawSaysItsJpegBacksUp() = runBlocking {
        val camera = "device-folder:external:camera"
        source.items += item(1, "PXL_20240501_101010123.RAW-02.ORIGINAL.dng", "image/x-adobe-dng").copy(deviceFolderId = camera)
        source.items += item(2, "20240502_090000.dng", "image/x-adobe-dng").copy(deviceFolderId = camera)
        source.items += item(3, "party.gif", "image/gif")
        source.items += item(4, "old-phone.3gp", "video/3gpp")
        source.items += item(5, "scan.tif", "image/tiff")
        source.items += item(6, "PXL_20240501_101010123.RAW-01.COVER.jpg", "image/jpeg").copy(deviceFolderId = camera)
        // Its JPEG is on the phone but outside what backup covers, so the RAW must not say that JPEG backs up.
        source.uncovered += item(7, "20240502_090000.jpg", "image/jpeg").copy(deviceFolderId = camera)
        val photos = enrolled()

        photos.syncNow()

        val rows = photos.observeTransfers().first().associateBy { it.displayName }
        assertEquals(StoredMessage.UPLOAD_RAW_NOT_ACCEPTED_JPEG_BACKS_UP.code, rows.getValue("PXL_20240501_101010123.RAW-02.ORIGINAL.dng").message)
        assertEquals(TransferViewState.VERIFIED, rows.getValue("PXL_20240501_101010123.RAW-01.COVER.jpg").state)
        assertEquals(StoredMessage.UPLOAD_RAW_NOT_ACCEPTED.code, rows.getValue("20240502_090000.dng").message)
        assertEquals(StoredMessage.UPLOAD_GIF_NOT_ACCEPTED.code, rows.getValue("party.gif").message)
        assertEquals(StoredMessage.UPLOAD_VIDEO_FORMAT_NOT_ACCEPTED.code, rows.getValue("old-phone.3gp").message)
        assertEquals(StoredMessage.UPLOAD_TIFF_NOT_ACCEPTED.code, rows.getValue("scan.tif").message)
        listOf("PXL_20240501_101010123.RAW-02.ORIGINAL.dng", "20240502_090000.dng", "party.gif", "old-phone.3gp", "scan.tif")
            .forEach { assertEquals(it, TransferViewState.UNSUPPORTED, rows.getValue(it).state) }
        assertEquals("Only the JPEG is uploaded", 1, protocol.uploadStarts)
    }

    @Test
    fun itemsAnEarlierVersionSkippedForTheirFormatAreJudgedAgainByTodaysRules() = runBlocking {
        // An earlier version skipped these with its generic reason; a mif1 HEIF with HEVC images now uploads.
        source.items += item(1, "party.gif", "image/gif")
        source.items += item(2, "pixel.heif", "image/heif", refuse = UnsupportedUploadException.MESSAGE)
        source.items += item(3, "broken.jpg", "image/jpeg", refuse = UnsupportedUploadException.INVALID)
        val photos = enrolled()
        photos.syncNow()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE transfers SET message = ? WHERE state = 'UNSUPPORTED' AND message = ?",
            arrayOf(UnsupportedUploadException.MESSAGE, UnsupportedUploadException.GIF),
        )
        val before = photos.observeTransfers().first().associateBy { it.displayName }
        assertEquals(UnsupportedUploadException.MESSAGE, before.getValue("party.gif").message)
        assertEquals(UnsupportedUploadException.MESSAGE, before.getValue("pixel.heif").message)

        // New rules come with a new discovery checkpoint, so every covered item is read again.
        source.refusals.remove(source.items[1].contentUri)
        database.openHelper.writableDatabase.execSQL("DELETE FROM media_store_checkpoints")
        photos.syncNow()

        val rows = photos.observeTransfers().first()
        val byName = rows.associateBy { it.displayName }
        assertEquals("A reason is updated in place, never reported twice", 3, rows.size)
        assertEquals(StoredMessage.UPLOAD_GIF_NOT_ACCEPTED.code, byName.getValue("party.gif").message)
        assertEquals(TransferViewState.UNSUPPORTED, byName.getValue("party.gif").state)
        assertEquals(TransferViewState.VERIFIED, byName.getValue("pixel.heif").state)
        // A skip that is not about the format is left as it was.
        assertEquals(UnsupportedUploadException.INVALID, byName.getValue("broken.jpg").message)
        assertEquals(1, protocol.uploadStarts)
    }

    @Test
    fun backupWaitsForWifiUnlessTheUserAllowsMobileData() = runBlocking {
        source.items += item(1, "camera.jpg", "image/jpeg")
        val photos = enrolled()
        network = BackupNetwork.METERED

        photos.syncNow()
        assertEquals(0, protocol.uploadStarts)

        settings = settings.copy(whileRoaming = true)
        network = BackupNetwork.ROAMING
        photos.syncNow()
        assertEquals("Roaming alone never spends mobile data", 0, protocol.uploadStarts)

        settings = settings.copy(photosOnMobileData = true)
        network = BackupNetwork.METERED
        photos.syncNow()
        assertEquals(TransferViewState.VERIFIED, photos.observeTransfers().first().single().state)
    }

    @Test
    fun backupWaitsForTheChargerOnlyWhenTheUserChoseIt() = runBlocking {
        source.items += item(1, "camera.jpg", "image/jpeg")
        val photos = enrolled()
        settings = settings.copy(onlyWhileCharging = true)
        charging = false

        photos.syncNow()
        assertEquals(0, protocol.uploadStarts)
        assertTrue("Nothing is staged off the charger either", photos.observeTransfers().first().isEmpty())
        val manual = photos.uploadSelected(UserSelectedPhoto("content://picker/one", "one.jpg"))
        photos.runUserUploads()
        assertEquals("A photo the user picked does not wait for the charger", TransferViewState.VERIFIED,
            photos.observeTransfers().first().single { it.id == manual.id }.state)

        charging = true
        photos.syncNow()
        assertEquals(TransferViewState.VERIFIED, photos.observeTransfers().first().single { it.displayName == "camera.jpg" }.state)
    }

    @Test
    fun aVerifiedUploadCarriesThePhonesFavouriteAndCaptionIntoICloudOnce() = runBlocking {
        source.items += item(1, "liked.jpg", "image/jpeg")
        source.items += item(2, "plain.jpg", "image/jpeg")
        source.metadata["content://media/external/images/media/1"] = PhoneMetadata(favorite = true, caption = "Lake at dawn")
        val photos = enrolled()

        repeat(3) { photos.syncNow() }

        assertTrue(photos.observeTransfers().first().all { it.state == TransferViewState.VERIFIED })
        assertEquals("Only the item with something to carry is edited, once", 1, protocol.applied.size)
        val (asset, metadata) = protocol.applied.single()
        assertTrue(asset.startsWith("asset:"))
        assertEquals(PhoneMetadata(favorite = true, caption = "Lake at dawn"), metadata)
    }

    @Test
    fun anAssetICloudAlreadyHadIsNotReFavourited() = runBlocking {
        source.items += item(1, "liked.jpg", "image/jpeg")
        source.metadata["content://media/external/images/media/1"] = PhoneMetadata(favorite = true, caption = "Lake at dawn")
        protocol.duplicate = true
        val photos = enrolled()

        repeat(3) { photos.syncNow() }

        assertEquals(TransferViewState.VERIFIED, photos.observeTransfers().first().single().state)
        assertEquals("The user may have un-favourited it in iCloud on purpose", 0, protocol.metadataAttempts)
    }

    @Test
    fun aFailedMetadataEditNeitherFailsNorRepeatsTheUpload() = runBlocking {
        source.items += item(1, "liked.jpg", "image/jpeg")
        source.metadata["content://media/external/images/media/1"] = PhoneMetadata(favorite = true)
        protocol.metadataFails = true
        val photos = enrolled()

        repeat(3) { photos.syncNow() }

        assertEquals(TransferViewState.VERIFIED, photos.observeTransfers().first().single().state)
        assertEquals(1, protocol.uploadStarts)
        assertEquals("The edit is tried once, when the upload is verified", 1, protocol.metadataAttempts)
    }

    @Test
    fun storageFullPausesEveryUploadUntilTheUserRetries() = runBlocking {
        source.items += item(1, "camera.jpg", "image/jpeg")
        val photos = enrolled()
        protocol.storageFull = true

        photos.syncNow()

        val waiting = photos.observeTransfers().first().single()
        assertEquals(StoredMessage.ICLOUD_STORAGE_FULL.code, waiting.message)
        assertNotNull(photos.observeBackup().first().storageFullSinceEpochMillis)
        assertEquals(StoredMessage.ICLOUD_STORAGE_FULL.code, photos.observeBackup().first().message)
        assertEquals(1, protocol.uploadStarts)

        protocol.storageFull = false
        photos.syncNow()
        assertEquals("A paused upload is not resumed by the next pass", 1, protocol.uploadStarts)

        photos.backup.retryAfterStorageFull()
        photos.syncNow()
        assertEquals(2, protocol.uploadStarts)
        assertEquals(TransferViewState.VERIFIED, photos.observeTransfers().first().single().state)
        assertNull(photos.observeBackup().first().storageFullSinceEpochMillis)
    }

    @Test
    fun aRemoteUploadPauseKeepsBackupPendingAndItResumesOnceItClears() = runBlocking {
        source.items += item(1, "camera.jpg", "image/jpeg")
        val photos = enrolled()
        remoteStatus.pause(ProtocolArea.UPLOADS)

        photos.syncNow()
        photos.syncNow()

        assertEquals("Nothing is sent while uploads are paused", 0, protocol.uploadStarts)
        assertTrue("Nothing fails while uploads are paused",
            photos.observeTransfers().first().all { it.state == TransferViewState.WAITING })
        val paused = photos.observeBackup().first()
        assertEquals("pause", paused.protocolPause?.id)
        assertEquals(StoredMessage.BACKUP_PAUSED_FOR_AN_ICLOUD_CHANGE.code, paused.message)
        assertEquals(BackupProgress(backedUp = 0, eligible = 1, pending = 1), photos.backup.backupProgress())

        remoteStatus.clear()
        photos.syncNow()

        assertEquals(1, protocol.uploadStarts)
        assertEquals(TransferViewState.VERIFIED, photos.observeTransfers().first().single().state)
        assertNull(photos.observeBackup().first().protocolPause)
    }

    @Test
    fun aRemoteUploadPauseKeepsTheUsersUploadsQueued() = runBlocking {
        val photos = companion()
        remoteStatus.pause(ProtocolArea.UPLOADS)
        photos.uploadSelected(UserSelectedPhoto("content://picker/one", "one.jpg"))

        // The transfer job ends rather than failing or retrying in a loop; the upload waits in the queue.
        photos.runUserUploads()
        assertEquals(0, protocol.uploadStarts)
        assertEquals(TransferViewState.WAITING, photos.observeTransfers().first().single().state)
        assertTrue(photos.hasPendingUserUploads())

        remoteStatus.clear()
        photos.runUserUploads()
        assertEquals(TransferViewState.VERIFIED, photos.observeTransfers().first().single().state)
    }

    @Test
    fun aPauseOfAnotherAreaDoesNotStopUploads() = runBlocking {
        source.items += item(1, "camera.jpg", "image/jpeg")
        val photos = enrolled()
        remoteStatus.pause(ProtocolArea.LIBRARY_EDITS, ProtocolArea.SHARED_ALBUMS)

        photos.syncNow()

        assertEquals(TransferViewState.VERIFIED, photos.observeTransfers().first().single().state)
        assertNull(photos.observeBackup().first().protocolPause)
    }

    @Test
    fun onlyItemsThatSentNothingCanBeRetried() = runBlocking {
        val photos = companion()
        val ticket = photos.uploadSelected(UserSelectedPhoto("content://picker/one", "one.jpg"))
        photos.cancelUserUploads()
        val canceled = photos.observeTransfers().first().single()
        assertEquals(TransferViewState.NEEDS_ATTENTION, canceled.state)
        assertTrue(canceled.retryable)

        photos.backup.retryTransfer(ticket.id)
        val retried = photos.observeTransfers().first().single()
        assertFalse("The retry replaces the canceled row", retried.id == ticket.id)
        photos.runUserUploads()
        val verified = photos.observeTransfers().first().single()
        assertEquals(TransferViewState.VERIFIED, verified.state)
        assertFalse(verified.retryable)

        try {
            photos.backup.retryTransfer(verified.id)
            fail("A sent upload must never be retried blindly")
        } catch (refused: PhotoCompanionException.Refused) {
            assertEquals(StoredMessage.ONLY_ITEMS_THAT_SENT_NOTHING_CAN_BE_RETRIED, refused.reason)
        }
        assertEquals(1, protocol.uploadStarts)
    }

    @Test
    fun anOlderConsentKeepsItsScopeAndAsksAgain() = runBlocking {
        source.items += item(1, "camera.png", "image/png")
        val photos = enrolled(BackupScope.CAMERA_JPEGS)
        photos.syncNow()
        assertFalse(photos.observeBackup().first().disclosureCurrent)
        assertEquals(BackupScope.CAMERA_JPEGS, source.lastScope)

        val current = enrolled(BackupScope.CAMERA_MEDIA, photos)
        assertTrue(current.observeBackup().first().disclosureCurrent)
    }

    @Test
    fun freeUpSpaceRechecksTheBatchAndLeavesOutItemsThatNoLongerMatch() = runBlocking {
        source.items += item(1, "one.jpg", "image/jpeg")
        source.items += item(2, "two.jpg", "image/jpeg")
        val photos = enrolled()
        photos.syncNow()
        photos.syncNow()
        source.items.forEach { record -> database.catalogDao().upsertItems(listOf(catalogRow(record))) }

        val candidates = photos.backup.freeUpSpaceCandidates()
        assertEquals(source.items.map { it.id }, candidates.mediaIds)
        assertEquals(source.items.sumOf { it.byteCount }, candidates.byteCount)

        source.changed += source.items[1].contentUri
        val progress = mutableListOf<Pair<Int, Int>>()
        val proposal = photos.prepareTrash(candidates.mediaIds.toSet(), allowPartial = true) { done, total -> progress += done to total }
        assertNull(proposal.blockedReason)
        assertEquals(setOf(source.items[0].id), proposal.eligibleMediaIds)
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)

        val strict = photos.prepareTrash(candidates.mediaIds.toSet())
        assertEquals(StoredMessage.THE_CURRENT_LOCAL_AND_ICLOUD_ORIGINALS_NO_LONGER_MATCH.code, strict.blockedReason)
    }

    @Test
    fun aLongBatchIsSplitSoNoCheckIsOlderThanTheConsentWindow() = runBlocking {
        (1L..3L).forEach { source.items += item(it, "$it.jpg", "image/jpeg") }
        val photos = enrolled()
        repeat(3) { photos.syncNow() }
        source.items.forEach { record -> database.catalogDao().upsertItems(listOf(catalogRow(record))) }
        // Each check takes 40 s, so the third would start after the batch's checking budget.
        source.onHash = { clock += 40_000; elapsed += 40_000 }
        val started = clock
        val startedElapsed = elapsed

        val first = photos.prepareTrash(source.items.map { it.id }.toSet(), allowPartial = true)
        assertEquals(source.items.take(2).map { it.id }.toSet(), first.eligibleMediaIds)
        assertEquals(listOf(source.items[2].id), first.unchecked)
        assertEquals("The window runs from the first check", started + 120_000, first.expiresAtEpochMillis)

        clock = started + 121_000
        elapsed = startedElapsed + 121_000
        try {
            photos.beginTrash(first.id)
            fail("A check older than the consent window must not reach Android")
        } catch (refused: PhotoCompanionException.Refused) {
            assertEquals(StoredMessage.TRASH_PROPOSAL_EXPIRED, refused.reason)
        }
    }

    @Test
    fun settingTheWallClockBackDoesNotReviveAnOldCheck() = runBlocking {
        source.items += item(1, "one.jpg", "image/jpeg")
        val photos = enrolled()
        photos.syncNow()
        database.catalogDao().upsertItems(listOf(catalogRow(source.items.single())))
        val proposal = photos.prepareTrash(setOf(source.items.single().id), allowPartial = true)

        elapsed += 121_000
        clock -= 3_600_000
        try {
            photos.beginTrash(proposal.id)
            fail("The consent window is measured on a clock that never moves back")
        } catch (refused: PhotoCompanionException.Refused) {
            assertEquals(StoredMessage.TRASH_PROPOSAL_EXPIRED, refused.reason)
        }
    }

    @Test
    fun aPhoneFileWhoseRevisionCannotBeReadStaysOnThePhone() = runBlocking {
        source.items += item(1, "one.jpg", "image/jpeg")
        source.items += item(2, "two.jpg", "image/jpeg")
        val photos = enrolled()
        photos.syncNow()
        photos.syncNow()
        source.items.forEach { record -> database.catalogDao().upsertItems(listOf(catalogRow(record))) }
        val proposal = photos.prepareTrash(source.items.map { it.id }.toSet(), allowPartial = true)

        source.revisionUnknown += source.items[1].contentUri
        assertEquals(setOf(source.items[0].id), photos.beginTrash(proposal.id).mediaIds)
    }

    @Test
    fun aPhoneFileEditedAfterItsCheckIsLeftOutOfTheTrashRequest() = runBlocking {
        source.items += item(1, "one.jpg", "image/jpeg")
        source.items += item(2, "two.jpg", "image/jpeg")
        val photos = enrolled()
        photos.syncNow()
        photos.syncNow()
        source.items.forEach { record -> database.catalogDao().upsertItems(listOf(catalogRow(record))) }
        val proposal = photos.prepareTrash(source.items.map { it.id }.toSet(), allowPartial = true)
        assertEquals(2, proposal.eligibleMediaIds.size)

        source.editedAfterCheck += source.items[1].contentUri
        val handoff = photos.beginTrash(proposal.id)
        assertEquals(setOf(source.items[0].id), handoff.mediaIds)
    }

    @Test
    fun aFileThatNeverReadsIsSkippedAfterRepeatedFailures() = runBlocking {
        source.items += item(1, "broken.jpg", "image/jpeg")
        source.items += item(2, "fine.jpg", "image/jpeg")
        source.unreadable += source.items[0].contentUri
        val photos = enrolled()

        repeat(2) { runCatching { photos.syncNow() } }
        assertTrue("Early failures retry the same item", photos.observeTransfers().first().isEmpty())
        runCatching { photos.syncNow() }
        photos.syncNow()

        val rows = photos.observeTransfers().first().associateBy { it.displayName }
        assertEquals(TransferViewState.UNSUPPORTED, rows.getValue("broken.jpg").state)
        assertEquals(StoredMessage.UPLOAD_STAGE_FAILED.code, rows.getValue("broken.jpg").message)
        assertEquals(TransferViewState.VERIFIED, rows.getValue("fine.jpg").state)
    }

    @Test
    fun aRetriedBackupItemStaysUnderTheNetworkSettings() = runBlocking {
        source.items += item(1, "late.webp", "image/webp", refuse = UnsupportedUploadException.ANIMATED)
        val photos = enrolled()
        photos.syncNow()
        val skipped = photos.observeTransfers().first().single()
        source.refusals.clear()
        network = BackupNetwork.METERED

        photos.backup.retryTransfer(skipped.id)
        photos.syncNow()
        val retried = photos.observeTransfers().first().single()
        assertTrue(retried.automatic)
        assertEquals("Mobile data is still off for backup", 0, protocol.uploadStarts)
    }

    @Test
    fun progressCountsVerifiedItemsAgainstEverythingBackupCovers() = runBlocking {
        source.items += item(1, "one.jpg", "image/jpeg")
        source.items += item(2, "two.jpg", "image/jpeg")
        val photos = enrolled()
        photos.syncNow()

        val progress = photos.backup.backupProgress()
        assertEquals(1, progress.backedUp)
        assertEquals(2, progress.eligible)
    }

    @Test
    fun anUploadInterruptedBeforeRegistrationStartsAFreshAttemptAfterABackoff() = runBlocking {
        source.items += item(1, "video.mp4", "image/jpeg")
        val photos = enrolled()
        protocol.interruptions = 1

        photos.syncNow()
        val waiting = photos.observeTransfers().first().single()
        assertEquals(TransferViewState.WAITING, waiting.state)
        assertEquals(StoredMessage.UPLOAD_INTERRUPTED_TRYING_AGAIN.code, waiting.message)

        val idle = photos.wake(WakeReason.BACKGROUND)
        assertEquals("The fresh attempt waits out its backoff", 1, protocol.uploadStarts)
        assertEquals("A background run knows when to come back", clock + Reconciler.retryBackoffMillis(0), idle.nextWorkAtEpochMillis)

        clock += Reconciler.retryBackoffMillis(0)
        photos.syncNow()
        assertEquals(2, protocol.uploadStarts)
        assertEquals(TransferViewState.VERIFIED, photos.observeTransfers().first().single().state)
        val transferId = photos.observeTransfers().first().single().id.value
        val attempts = database.query("SELECT attemptId, predecessorAttemptId FROM upload_attempts WHERE transferId = ?", arrayOf(transferId)).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(0) to rows.getString(1)) }
        }
        assertEquals(2, attempts.size)
        assertEquals("The fresh attempt names the one it replaces", attempts.first { it.second == null }.first, attempts.first { it.second != null }.second)
        assertEquals("Nothing is suppressed", 0, database.query("SELECT COUNT(*) FROM upload_suppressions", null).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test
    fun automaticRetriesStopAfterTheCapAndTheUserCanRetry() = runBlocking {
        source.items += item(1, "camera.jpg", "image/jpeg")
        val photos = enrolled()
        protocol.interruptions = Reconciler.MAX_AUTOMATIC_RETRIES + 1

        repeat(Reconciler.MAX_AUTOMATIC_RETRIES + 1) {
            photos.syncNow()
            clock += Reconciler.retryBackoffMillis(it)
        }
        val stopped = photos.observeTransfers().first().single()
        assertEquals(TransferViewState.NEEDS_ATTENTION, stopped.state)
        assertEquals(StoredMessage.UPLOAD_INTERRUPTED_REPEATEDLY.code, stopped.message)
        assertTrue("Nothing reached iCloud, so the user can retry", stopped.retryable)
        assertEquals(Reconciler.MAX_AUTOMATIC_RETRIES + 1, protocol.uploadStarts)
        photos.syncNow()
        assertEquals("No automatic attempt after the cap", Reconciler.MAX_AUTOMATIC_RETRIES + 1, protocol.uploadStarts)

        photos.backup.retryTransfer(stopped.id)
        photos.syncNow()
        assertEquals(TransferViewState.VERIFIED, photos.observeTransfers().first().single().state)
    }

    @Test
    fun retryingAfterTheCapReleasesTheOldStagedCopy() = runBlocking {
        source.items += item(1, "camera.jpg", "image/jpeg")
        val photos = enrolled()
        protocol.interruptions = Reconciler.MAX_AUTOMATIC_RETRIES + 1
        repeat(Reconciler.MAX_AUTOMATIC_RETRIES + 1) {
            photos.syncNow()
            clock += Reconciler.retryBackoffMillis(it)
        }
        val stopped = photos.observeTransfers().first().single()

        // Retried at once, before any later pass could release the stopped item's copy.
        photos.backup.retryTransfer(stopped.id)

        val unreleased = database.query("SELECT COUNT(*) FROM staged_sources WHERE releasedAtEpochMillis IS NULL", null)
            .use { it.moveToFirst(); it.getInt(0) }
        assertEquals("Only the new transfer's copy is kept", 1, unreleased)
        val files = java.io.File(context.filesDir, "upload_staging").listFiles().orEmpty().filter { it.name.endsWith(".original") }
        assertEquals(1, files.size)
    }

    @Test
    fun aNetworkChangeAfterAnUploadWasArmedReleasesItWithoutWaiting() = runBlocking {
        source.items += item(1, "camera.jpg", "image/jpeg")
        val photos = enrolled()
        // The network turns metered between arming the attempt and sending it: the check before arming still sees Wi-Fi,
        // the one after it sees mobile data. Nothing left the phone.
        protocol.onCaptureChangeToken = { meteredAfterChecks = 1 }
        photos.syncNow()
        protocol.onCaptureChangeToken = {}

        val waiting = photos.observeTransfers().first().single()
        assertEquals(TransferViewState.WAITING, waiting.state)
        assertEquals("Nothing was sent", 0, protocol.uploadStarts)
        assertEquals("Nothing is suppressed", 0, database.query("SELECT COUNT(*) FROM upload_suppressions", null).use { it.moveToFirst(); it.getInt(0) })

        network = BackupNetwork.UNMETERED
        photos.syncNow()
        assertEquals(TransferViewState.VERIFIED, photos.observeTransfers().first().single().state)
        assertEquals(1, protocol.uploadStarts)
    }

    @Test
    fun pendingWorkLeavesOutItemsBackupCannotDoOnItsOwn() = runBlocking {
        (1L..12L).forEach { source.items += item(it, "$it.avif", "image/avif") }
        source.items += item(20, "a.jpg", "image/jpeg")
        source.items += item(21, "b.jpg", "image/jpeg")
        val photos = enrolled()
        assertEquals("Nothing discovered yet: all of it is to do", 14, photos.backup.backupProgress().pending)

        val startedAt = clock
        photos.syncNow()

        val progress = photos.backup.backupProgress()
        assertEquals(14, progress.eligible)
        assertEquals("Twelve unsupported items are skipped, not pending; one photo is left", 1, progress.pending)
        assertEquals(1, photos.backup.verifiedSince(startedAt))
    }

    @Test
    fun anUploadThatMayHaveBeenRegisteredIsCheckedAndNeverSentAgain() = runBlocking {
        source.items += item(1, "camera.jpg", "image/jpeg")
        val photos = enrolled()
        protocol.interruptions = 1
        protocol.interruptedAfterRegistration = true

        photos.syncNow()
        assertEquals(TransferViewState.CHECKING_ICLOUD, photos.observeTransfers().first().single().state)
        clock += RECONCILIATION_DEADLINE_MILLIS
        photos.syncNow()
        photos.syncNow()

        assertEquals(TransferViewState.UNRESOLVED, photos.observeTransfers().first().single().state)
        assertEquals(1, protocol.uploadStarts)
    }

    @Test
    fun iCloudIsGivenTheCaptureTimeRatherThanWhenTheFileWasLastModified() = runBlocking {
        source.items += item(1, "taken.jpg", "image/jpeg").also { source.dateTaken[it.contentUri] = 1_600_000_000_000L }
        source.items += item(2, "undated.jpg", "image/jpeg")
        val photos = enrolled()

        repeat(2) { photos.syncNow() }

        assertEquals(
            listOf(Triple("taken.jpg", 1_600_000_000_000L, null), Triple("undated.jpg", MODIFIED_AT, null)),
            protocol.preparedDates,
        )
    }

    @Test
    fun existingPhotosBackUpNewestFirstAfterWhatIsNew() = runBlocking {
        source.existing += item(10, "old.jpg", "image/jpeg", capturedAt = 100)
        source.existing += item(11, "newest-existing.jpg", "image/jpeg", capturedAt = 300)
        source.existing += item(12, "middle.jpg", "image/jpeg", capturedAt = 200)
        source.items += item(1, "new.jpg", "image/jpeg")
        val photos = enrolled(includeExisting = true)
        assertTrue(photos.observeBackup().first().includesExisting)

        repeat(5) { photos.syncNow() }

        assertEquals(listOf("new.jpg", "newest-existing.jpg", "middle.jpg", "old.jpg"), protocol.uploadedNames)
        assertTrue(photos.observeTransfers().first().all { it.state == TransferViewState.VERIFIED })
        assertEquals(BackupProgress(4, 4), photos.backup.backupProgress())
    }

    @Test
    fun onlyNewItemsBackUpUntilTheUserIncludesExistingOnes() = runBlocking {
        source.existing += item(10, "old.jpg", "image/jpeg", capturedAt = 100)
        source.items += item(1, "new.jpg", "image/jpeg")
        val photos = enrolled()

        repeat(3) { photos.syncNow() }
        assertEquals(listOf("new.jpg"), protocol.uploadedNames)
        assertEquals("Progress counts only what backup covers", 1, photos.backup.backupProgress().eligible)

        val widened = photos.changeBackup(BackupChange.IncludeExisting)
        assertTrue(widened.includesExisting)
        repeat(2) { photos.syncNow() }
        assertEquals(listOf("new.jpg", "old.jpg"), protocol.uploadedNames)
        assertEquals(2, photos.backup.backupProgress().backedUp)
    }

    @Test
    fun anExistingCopyOfBytesAlreadyBackedUpIsNotUploadedAgain() = runBlocking {
        source.items += item(1, "IMG_1.jpg", "image/jpeg")
        source.existing += item(10, "IMG_1 (copy).jpg", "image/jpeg", capturedAt = 100).also { source.sameBytes[it.contentUri] = 1 }
        source.existing += item(11, "other.jpg", "image/jpeg", capturedAt = 50)
        val photos = enrolled(includeExisting = true)

        repeat(4) { photos.syncNow() }

        assertEquals(listOf("IMG_1.jpg", "other.jpg"), protocol.uploadedNames)
    }

    @Test
    fun anExistingItemRedatedPastTheCursorDuringTheReadIsStillBackedUp() = runBlocking {
        source.existing += item(10, "newest.jpg", "image/jpeg", capturedAt = 300)
        source.existing += item(11, "middle.jpg", "image/jpeg", capturedAt = 200)
        source.existing += item(12, "oldest.jpg", "image/jpeg", capturedAt = 100)
        val photos = enrolled(includeExisting = true)
        photos.syncNow()
        assertEquals(listOf("newest.jpg"), protocol.uploadedNames)

        // Edited to a capture time newer than where the read is: it is now behind the cursor.
        val index = source.existing.indexOfFirst { it.fileName == "oldest.jpg" }
        source.existing[index] = source.existing[index].copy(capturedAtEpochMillis = 400)
        repeat(4) { photos.syncNow() }

        assertEquals(setOf("newest.jpg", "middle.jpg", "oldest.jpg"), protocol.uploadedNames.toSet())
        assertEquals(3, protocol.uploadedNames.size)
        val reads = source.existingPagesRead
        repeat(2) { photos.syncNow() }
        assertEquals("A complete scan is not read again", reads, source.existingPagesRead)
    }

    @Test
    fun aLargeExistingLibraryIsReadAFewPagesAPassAndResumesWhereItStopped() = runBlocking {
        // Nothing here can be staged, so each pass reads its page budget and saves where it got to.
        (100L..399L).forEach { source.existing += item(it, "$it.avif", "image/avif", capturedAt = it) }
        val photos = enrolled(includeExisting = true)

        photos.syncNow()
        assertEquals(10, source.existingPagesRead)
        assertEquals(200, photos.observeTransfers().first().size)

        photos.syncNow()
        assertEquals("The second pass starts after the first one's last item", 15, source.existingPagesRead)
        assertEquals(300, photos.observeTransfers().first().size)
        assertEquals(0, protocol.uploadStarts)
    }

    private suspend fun enrolled(
        scope: BackupScope = BackupScope.CAMERA_MEDIA,
        photos: DefaultPhotoCompanion = companion(),
        includeExisting: Boolean = false,
    ): DefaultPhotoCompanion {
        val draft = photos.beginBackupEnrollment(scope)
        photos.acceptBackupDisclosure(draft.id)
        photos.finishBackupEnrollment(draft.id, includeExisting)
        return photos
    }

    private fun companion() = DefaultPhotoCompanion(
        database = database,
        originals = source,
        staging = staging,
        protocol = protocol,
        scheduler = NoOpScheduler,
        automaticBackupPolicy = policy,
        now = { clock },
        elapsedRealtime = { elapsed },
        protocolStatus = remoteStatus,
    )

    private fun item(id: Long, name: String, mime: String, refuse: String? = null, capturedAt: Long = NOW + id) = DeviceMediaRecord(
        id = "device:external:$id",
        fileName = name,
        capturedAtEpochMillis = capturedAt,
        width = 10,
        height = 10,
        contentUri = "content://media/external/images/media/$id",
        sourceRevision = "rev:$id",
        volumeName = "external",
        volumeVersion = "v1",
        mediaStoreId = id,
        generationModified = id,
        mimeType = mime,
        byteCount = bytes(id).size.toLong(),
    ).also { if (refuse != null) source.refusals[it.contentUri] = refuse }

    private fun catalogRow(record: DeviceMediaRecord) = CatalogItemEntity(
        mediaId = record.id, fileName = record.fileName, capturedAtEpochMillis = record.capturedAtEpochMillis,
        width = 10, height = 10, origin = "DEVICE", sourceRevision = record.sourceRevision, localUri = record.contentUri,
        previewCachePath = null, originalCachePath = null, accentStartArgb = 0, accentEndArgb = 0, lastSeenScanId = "scan",
    )

    private class FakeSource(private val context: Context) : ExactOriginalSource {
        val items = mutableListOf<DeviceMediaRecord>()

        /** Items already on the phone at enrollment; only [discoverExistingMedia] returns them. */
        val existing = mutableListOf<DeviceMediaRecord>()

        /** Content URIs whose bytes are those of another item's media store id. */
        val sameBytes = mutableMapOf<String, Long>()
        var existingPagesRead = 0

        /** MediaStore's DATE_TAKEN by content URI; items without one have only their modification date. */
        val dateTaken = mutableMapOf<String, Long>()
        val refusals = mutableMapOf<String, String>()

        /** What the phone records about an item beyond its bytes, by content URI. */
        val metadata = mutableMapOf<String, PhoneMetadata>()
        val changed = mutableSetOf<String>()
        val unreadable = mutableSetOf<String>()
        val editedAfterCheck = mutableSetOf<String>()
        val revisionUnknown = mutableSetOf<String>()
        var onHash: () -> Unit = {}
        var lastScope: BackupScope? = null

        /** JPEGs on the phone that backup does not cover, so they never count as a RAW's companion. */
        val uncovered = mutableListOf<DeviceMediaRecord>()

        override suspend fun companionJpegNames(
            raw: DeviceMediaRecord, scope: BackupScope, folders: Set<String>, addedAfterEpochMillis: Long,
            enrollmentBaseline: LocalDiscoveryCheckpoint?, includeExisting: Boolean,
        ) = (items + if (includeExisting) existing else emptyList())
            .filter { it.deviceFolderId == raw.deviceFolderId && it.mimeType == "image/jpeg" }
            .map { it.fileName }

        override fun permissionFingerprint() = "full:unredacted:test"
        override fun hasAutomaticBackupAccess() = true
        override suspend fun captureEnrollmentBaseline() = LocalDiscoveryCheckpoint("external", "v1", 0, Long.MAX_VALUE)

        override suspend fun discoverEligibleMedia(
            checkpoint: LocalDiscoveryCheckpoint?, limit: Int, scope: BackupScope, folders: Set<String>,
            addedAfterEpochMillis: Long, enrollmentBaseline: LocalDiscoveryCheckpoint?,
        ): LocalDiscoveryPage {
            lastScope = scope
            val after = checkpoint?.generationModified ?: -1L
            val remaining = items.filter { it.generationModified > after }
            val page = remaining.take(limit)
            return LocalDiscoveryPage(
                page,
                LocalDiscoveryCheckpoint("external", "v1", page.lastOrNull()?.generationModified ?: after, page.lastOrNull()?.mediaStoreId ?: -1),
                remaining.size > limit,
            )
        }

        override suspend fun discoverExistingMedia(
            cursor: ExistingMediaCursor?, limit: Int, scope: BackupScope, folders: Set<String>,
            addedAfterEpochMillis: Long, enrollmentBaseline: LocalDiscoveryCheckpoint?,
        ): ExistingMediaPage {
            existingPagesRead += 1
            val newestFirst = existing.sortedWith(compareByDescending<DeviceMediaRecord> { it.capturedAtEpochMillis }.thenByDescending { it.mediaStoreId })
                .map { ExistingMedia(it, ExistingMediaCursor("v1", it.capturedAtEpochMillis, it.mediaStoreId)) }
            val remaining = if (cursor == null) newestFirst else newestFirst.filter {
                it.cursor.sortKeyEpochMillis < cursor.sortKeyEpochMillis ||
                    (it.cursor.sortKeyEpochMillis == cursor.sortKeyEpochMillis && it.cursor.mediaStoreId < cursor.mediaStoreId)
            }
            return ExistingMediaPage(remaining.take(limit), remaining.size > limit)
        }

        override suspend fun eligibleMediaIds(
            scope: BackupScope, folders: Set<String>, addedAfterEpochMillis: Long, enrollmentBaseline: LocalDiscoveryCheckpoint?,
            includeExisting: Boolean,
        ) = (items + if (includeExisting) existing else emptyList()).map { it.id }.toSet()

        override suspend fun copySelected(contentUri: String, displayNameHint: String?, output: OutputStream) =
            evidence(contentUri, displayNameHint ?: "selected.jpg", output)

        override suspend fun copyMediaStoreItem(contentUri: String, expectedRevision: String, output: OutputStream): ExactOriginalEvidence {
            refusals[contentUri]?.let { throw UnsupportedUploadException(it) }
            check(contentUri !in unreadable) { "unreadable" }
            return evidence(contentUri, (items + existing).firstOrNull { it.contentUri == contentUri }?.fileName ?: "camera.jpg", output)
        }

        override suspend fun hashMediaStoreItem(contentUri: String, expectedRevision: String): ExactOriginalEvidence {
            onHash()
            return evidence(contentUri, "camera.jpg", null, if (contentUri in changed) byteArrayOf(-1, -40, -1, 9) else null)
        }

        override suspend fun currentRevision(contentUri: String) = when (contentUri) {
            in revisionUnknown -> null
            in editedAfterCheck -> "edited"
            else -> "rev"
        }

        override fun createTrashRequest(contentUris: List<String>): IntentSender = PendingIntent.getActivity(
            context, 44, Intent("me.lvca.alba.TEST_TRASH").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE,
        ).intentSender

        override suspend fun readTrashState(contentUri: String): LocalTrashState? = LocalTrashState(true, null)

        override suspend fun phoneMetadata(contentUri: String, mimeType: String) = metadata[contentUri] ?: PhoneMetadata()

        private fun evidence(contentUri: String, name: String, output: OutputStream?, override: ByteArray? = null): ExactOriginalEvidence {
            val id = sameBytes[contentUri] ?: (items + existing).firstOrNull { it.contentUri == contentUri }?.mediaStoreId
                ?: contentUri.hashCode().toLong()
            val bytes = override ?: bytes(id)
            output?.write(bytes)
            return ExactOriginalEvidence(
                contentUri = contentUri, displayName = name, mimeType = "image/jpeg", lineageKey = "lineage:$contentUri",
                sourceRevision = "rev", acquisitionMethod = "TEST", originalFormatRequested = true, unredacted = true,
                formatValidated = true, byteCount = bytes.size.toLong(), sha256Hex = sha256(bytes),
                lastModifiedAtEpochMillis = MODIFIED_AT, dateTakenEpochMillis = dateTaken[contentUri],
            )
        }
    }

    /** Accepts each upload and shows it back as one exact iCloud copy; can refuse reservations for lack of storage. */
    private class FakeProtocol : ICloudPhotoProtocol {
        var storageFull = false
        var uploadStarts = 0

        /** How many more uploads stop while their bytes are sent, before registration. */
        var interruptions = 0

        /** Whether an interrupted upload had already sent its registration, so it may be in iCloud. */
        var interruptedAfterRegistration = false
        private val interrupted = mutableSetOf<String>()
        val uploadedNames = mutableListOf<String>()
        val preparedDates = mutableListOf<Triple<String, Long?, Int?>>()
        private val uploads = mutableMapOf<String, ByteArray>()
        val applied = mutableListOf<Pair<String, PhoneMetadata>>()
        var metadataFails = false

        /** Whether Apple reports each upload as a copy of an asset already in the library (HTTP 409). */
        var duplicate = false
        var metadataAttempts = 0

        override suspend fun applyPhoneMetadata(binding: AccountBinding, assetRecordName: String, metadata: PhoneMetadata) {
            metadataAttempts += 1
            if (metadataFails) throw java.io.IOException("connection reset while editing")
            applied += assetRecordName to metadata
        }

        override suspend fun canResume(binding: AccountBinding, attemptId: String) = attemptId !in interrupted
        override suspend fun registrationNeverSent(binding: AccountBinding, attemptId: String) =
            attemptId in interrupted && !interruptedAfterRegistration
        override fun currentDestination() = ICloudDestination("account", "session", "test@example.com")
        var onCaptureChangeToken: () -> Unit = {}
        override suspend fun captureChangeToken(binding: AccountBinding) = "before".also { onCaptureChangeToken() }

        override suspend fun prepareOriginal(
            binding: AccountBinding, fileName: String, source: OneShotUploadSource, lastModifiedAtEpochMillis: Long?,
            captureOffsetMinutes: Int?,
        ): PreparedICloudUpload = object : PreparedICloudUpload {
            init { preparedDates += Triple(fileName, lastModifiedAtEpochMillis, captureOffsetMinutes) }

            override suspend fun startOnce(attemptId: String): UploadAcceptance {
                uploadStarts += 1
                if (storageFull) throw ICloudStorageFullException()
                if (interruptions > 0) {
                    interruptions -= 1
                    interrupted += attemptId
                    throw java.io.IOException("connection reset while sending bytes")
                }
                uploadedNames += fileName
                uploads[attemptId] = source.openOnce().use { it.readBytes() }
                return UploadAcceptance("request", "master:$attemptId", "asset:$attemptId", duplicate)
            }

            override fun cancel() = Unit
        }

        override suspend fun findUploadCandidates(
            binding: AccountBinding, afterChangeToken: String, acceptance: UploadAcceptance?, limit: Int,
        ) = CandidatePage(
            listOfNotNull(acceptance?.let { RemotePairCandidate(
                RemoteRecordRef(requireNotNull(it.masterRecordName), "tag"), RemoteRecordRef(requireNotNull(it.assetRecordName), "tag"),
                relationIsCurrent = true, masterDeleted = false, assetDeleted = false, originalResourceIdentity = "resource",
            ) }),
            nextChangeToken = "after",
            moreComing = false,
        )

        override suspend fun streamFreshOriginal(
            binding: AccountBinding, pair: RemotePairCandidate, output: OutputStream,
        ): RemoteOriginalObservation {
            val bytes = uploads.getValue(pair.master.name.removePrefix("master:"))
            output.write(bytes)
            return RemoteOriginalObservation("resource", bytes.size.toLong())
        }
    }

    /** A remote protocol status the test switches by hand. */
    private class SwitchableStatus : ProtocolStatusGate {
        private val current = MutableStateFlow<List<ProtocolPause>>(emptyList())
        override val pauses = current
        override suspend fun pauseFor(area: ProtocolArea) = current.value.firstOrNull { area in it.areas }
        fun pause(vararg areas: ProtocolArea) { current.value = listOf(ProtocolPause("pause", areas.toSet())) }
        fun clear() { current.value = emptyList() }
    }

    private object NoOpScheduler : WakeupScheduler {
        override suspend fun scheduleSoon() = Unit
        override suspend fun setAutomaticBackupEnabled(enabled: Boolean) = Unit
    }

    private companion object {
        const val NOW = 1_000_000L
        const val MODIFIED_AT = 1_700_000_000_000L

        fun bytes(id: Long) = byteArrayOf(-1, -40, -1, 1, 2, 3, id.toByte())

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { "%02x".format(it) }
    }
}
