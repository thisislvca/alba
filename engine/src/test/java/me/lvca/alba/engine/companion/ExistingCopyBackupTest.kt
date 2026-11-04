package me.lvca.alba.engine.companion

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.engine.cache.UploadStagingStore
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.CatalogCheckpointEntity
import me.lvca.alba.engine.database.CatalogItemEntity
import me.lvca.alba.engine.database.DisplayLink
import me.lvca.alba.engine.database.TransferEntity
import me.lvca.alba.engine.database.TransferState
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.AutomaticBackupPolicy
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.CandidatePage
import me.lvca.alba.engine.model.ICloudDestination
import me.lvca.alba.engine.model.ICloudPhotoProtocol
import me.lvca.alba.engine.model.OneShotUploadSource
import me.lvca.alba.engine.model.PreparedICloudUpload
import me.lvca.alba.engine.model.RemoteOriginalObservation
import me.lvca.alba.engine.model.RemotePairCandidate
import me.lvca.alba.engine.model.RemoteRecordRef
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.TransferViewState
import me.lvca.alba.engine.model.UploadAcceptance
import me.lvca.alba.engine.model.WakeupScheduler
import me.lvca.alba.engine.source.DeviceMediaRecord
import me.lvca.alba.engine.source.ExactOriginalEvidence
import me.lvca.alba.engine.source.ExactOriginalSource
import me.lvca.alba.engine.source.ExistingMediaCursor
import me.lvca.alba.engine.source.ExistingMediaPage
import me.lvca.alba.engine.source.LocalDiscoveryCheckpoint
import me.lvca.alba.engine.source.LocalDiscoveryPage
import me.lvca.alba.engine.source.LocalTrashState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.OutputStream
import java.security.MessageDigest

/** Automatic backup finds phone items already in the iCloud library by exact bytes, and uploads anything less certain. */
@RunWith(AndroidJUnit4::class)
class ExistingCopyBackupTest {
    private lateinit var context: Context
    private lateinit var database: AlbaDatabase
    private lateinit var staging: UploadStagingStore
    private val source by lazy { FakeSource(context) }
    private val protocol = FakeProtocol()
    private var clock = NOW
    private var catalogAccount: String? = ACCOUNT
    private var refreshRequests = 0
    private val policy = AutomaticBackupPolicy { true }

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
    fun anItemWhoseExactBytesAreInICloudIsLinkedWithoutUploading() = runBlocking {
        val phone = phoneItem(1, "IMG_0001.HEIC")
        syncedCatalog()
        libraryItem("one", "IMG_0001.HEIC", bytes(1), capturedAt = CAPTURED)
        val photos = enrolled()

        photos.syncNow()

        val row = photos.single()
        assertEquals(TransferViewState.VERIFIED, row.state)
        assertTrue(row.alreadyInICloud)
        assertEquals(StoredMessage.ALREADY_IN_ICLOUD.code, row.message)
        assertEquals("Nothing is uploaded", 0, protocol.uploadStarts)
        assertEquals("Only the one candidate is downloaded", listOf("asset-one"), protocol.downloads)
        assertEquals(1, photos.backup.backupProgress().backedUp)
        // Linked like a verified upload: a current proof against the library item, which Free up space re-checks.
        val proof = requireNotNull(database.verificationDao().findCurrentProof(phone.id, ACCOUNT))
        assertEquals(sha256(bytes(1)), proof.sha256Hex)
        assertEquals("master-one", database.verificationDao().findRemotePair(proof.remotePairId)?.masterRecordName)

        photos.syncNow()
        assertEquals("The item is not looked up or uploaded again", 0, protocol.uploadStarts)
        assertEquals(1, protocol.downloads.size)

        assertEquals(listOf(phone.id), photos.backup.freeUpSpaceCandidates().mediaIds)
        assertEquals("Free up space re-reads both copies", setOf(phone.id), photos.prepareTrash(setOf(phone.id)).eligibleMediaIds)
        assertEquals(2, protocol.downloads.size)
    }

    @Test
    fun theSameNameSizeAndDateWithDifferentBytesUploads() = runBlocking {
        phoneItem(1, "IMG_0001.HEIC")
        syncedCatalog()
        libraryItem("one", "IMG_0001.HEIC", bytes(9), capturedAt = CAPTURED)
        val photos = enrolled()

        photos.syncNow()

        val row = photos.single()
        assertEquals(TransferViewState.VERIFIED, row.state)
        assertFalse(row.alreadyInICloud)
        assertEquals(1, protocol.uploadStarts)
        assertEquals(1, protocol.downloads.size)
    }

    @Test
    fun twoExactCopiesInICloudAreAmbiguousAndUpload() = runBlocking {
        phoneItem(1, "IMG_0001.HEIC")
        syncedCatalog()
        libraryItem("one", "IMG_0001.HEIC", bytes(1), capturedAt = CAPTURED)
        libraryItem("two", "IMG_0001.HEIC", bytes(1), capturedAt = CAPTURED)
        val photos = enrolled()

        photos.syncNow()

        assertFalse(photos.single().alreadyInICloud)
        assertEquals(1, protocol.uploadStarts)
        assertNull(database.verificationDao().findRemotePair(remotePairId(BINDING, protocol.pairFor("one"))))
    }

    @Test
    fun tooManyLookAlikesUploadWithoutDownloadingAny() = runBlocking {
        phoneItem(1, "IMG_0001.HEIC")
        syncedCatalog()
        (1..3).forEach { libraryItem("n$it", "IMG_0001.HEIC", bytes(1), capturedAt = CAPTURED) }
        val photos = enrolled()

        photos.syncNow()

        assertEquals(1, protocol.uploadStarts)
        assertTrue(protocol.downloads.isEmpty())
    }

    @Test
    fun aLibraryItemNoLongerInTheLibraryIsNotAMatch() = runBlocking {
        phoneItem(1, "IMG_0001.HEIC")
        syncedCatalog()
        libraryItem("one", "IMG_0001.HEIC", bytes(1), capturedAt = CAPTURED)
        protocol.recentlyDeleted += "asset-one"
        val photos = enrolled()

        photos.syncNow()

        assertFalse(photos.single().alreadyInICloud)
        assertEquals(1, protocol.uploadStarts)
        assertTrue("A deleted item's original is never downloaded", protocol.downloads.isEmpty())
    }

    @Test
    fun aLargerICloudOriginalStopsDownloadingAtThePhoneFilesSize() = runBlocking {
        phoneItem(1, "IMG_0001.HEIC")
        syncedCatalog()
        // The catalog lists the phone file's size, but iCloud now serves a longer original.
        libraryItem("one", "IMG_0001.HEIC", bytes(1) + ByteArray(4096), capturedAt = CAPTURED, listedSize = bytes(1).size.toLong())
        val photos = enrolled()

        photos.syncNow()

        assertFalse(photos.single().alreadyInICloud)
        assertEquals(1, protocol.uploadStarts)
        assertTrue(protocol.bytesStreamed <= bytes(1).size)
    }

    @Test
    fun anUnsyncedLibraryHoldsBackupUntilItIsReadThenMatches() = runBlocking {
        phoneItem(1, "IMG_0001.HEIC")
        val photos = enrolled()

        photos.syncNow()

        val waiting = photos.single()
        assertEquals(TransferViewState.WAITING, waiting.state)
        assertEquals(StoredMessage.CHECKING_ICLOUD_LIBRARY_FIRST.code, waiting.message)
        assertEquals(0, protocol.uploadStarts)
        assertTrue("A library read is asked for", refreshRequests > 0)

        syncedCatalog()
        libraryItem("one", "IMG_0001.HEIC", bytes(1), capturedAt = CAPTURED)
        clock += ExistingCopyCheck.LIBRARY_RETRY_MILLIS
        photos.syncNow()

        assertTrue(photos.single().alreadyInICloud)
        assertEquals(0, protocol.uploadStarts)
    }

    @Test
    fun aLibraryStillUnreadADayAfterBackupWasTurnedOnNoLongerHoldsItBack() = runBlocking {
        phoneItem(1, "IMG_0001.HEIC")
        val photos = enrolled()
        photos.syncNow()
        assertEquals(0, protocol.uploadStarts)

        clock += ExistingCopyCheck.FIRST_SYNC_WAIT_MILLIS
        photos.syncNow()

        assertEquals(TransferViewState.VERIFIED, photos.single().state)
        assertEquals(1, protocol.uploadStarts)
    }

    @Test
    fun aStaleLibraryIsStillCheckedAndAskedToRefresh() = runBlocking {
        phoneItem(1, "IMG_0001.HEIC")
        syncedCatalog(refreshedAt = NOW - 3 * CatalogLibraryIndex.DEFAULT_MAX_AGE_MILLIS)
        libraryItem("one", "IMG_0001.HEIC", bytes(1), capturedAt = CAPTURED)
        val photos = enrolled()

        photos.syncNow()

        assertTrue(photos.single().alreadyInICloud)
        assertTrue(refreshRequests > 0)
    }

    @Test
    fun aCatalogOfAnotherAccountIsNeverUsed() = runBlocking {
        phoneItem(1, "IMG_0001.HEIC")
        syncedCatalog()
        libraryItem("one", "IMG_0001.HEIC", bytes(1), capturedAt = CAPTURED)
        catalogAccount = "someone-else"
        val photos = enrolled()

        photos.syncNow()

        assertFalse(photos.single().alreadyInICloud)
        assertEquals(1, protocol.uploadStarts)
        assertTrue(protocol.lookups.isEmpty())
    }

    @Test
    fun aFailedICloudReadRetriesThenUploadsOnceItHasWaitedLongEnough() = runBlocking {
        phoneItem(1, "IMG_0001.HEIC")
        syncedCatalog()
        libraryItem("one", "IMG_0001.HEIC", bytes(1), capturedAt = CAPTURED)
        protocol.failDownloads = true
        val photos = enrolled()

        photos.syncNow()
        assertEquals(StoredMessage.CHECKING_ICLOUD_LIBRARY_FIRST.code, photos.single().message)
        assertEquals(0, protocol.uploadStarts)

        clock += ExistingCopyCheck.CHECK_FAILURE_WINDOW_MILLIS
        photos.syncNow()

        assertFalse(photos.single().alreadyInICloud)
        assertEquals(1, protocol.uploadStarts)
    }

    @Test
    fun aPhoneItemFoundInICloudMergesWithItsICloudCopyInTheGallery() = runBlocking {
        val phone = phoneItem(1, "IMG_0001.HEIC")
        syncedCatalog()
        libraryItem("one", "IMG_0001.HEIC", bytes(1), capturedAt = CAPTURED)
        val photos = enrolled()

        photos.syncNow()

        assertEquals(
            listOf(DisplayLink(cloudId = "icloud:$NAMESPACE:asset-one", deviceId = phone.id, accountId = ACCOUNT)),
            database.catalogDao().observeDisplayLinks().first(),
        )
    }

    @Test
    fun anUploadedPhoneItemMergesWithItsICloudCopyOnceTheLibraryListsIt() = runBlocking {
        val phone = phoneItem(1, "IMG_0001.HEIC")
        syncedCatalog()
        val photos = enrolled()

        photos.syncNow()
        assertEquals(TransferViewState.VERIFIED, photos.single().state)
        assertEquals(1, protocol.uploadStarts)
        assertTrue(database.catalogDao().observeDisplayLinks().first().isEmpty())

        // The next library sync brings in the asset the upload created.
        val pair = requireNotNull(database.verificationDao().findCurrentProof(phone.id, ACCOUNT))
            .let { requireNotNull(database.verificationDao().findRemotePair(it.remotePairId)) }
        database.catalogDao().upsertMetadata(listOf(CatalogItemEntity(
            mediaId = "icloud:$NAMESPACE:${pair.assetRecordName}", fileName = phone.fileName, capturedAtEpochMillis = CAPTURED,
            width = 10, height = 10, origin = "ICLOUD", sourceRevision = "${pair.masterChangeTag}:${pair.assetChangeTag}",
            localUri = null, previewCachePath = null, originalCachePath = null, accentStartArgb = 0, accentEndArgb = 0,
            lastSeenScanId = "scan", masterRecordName = pair.masterRecordName, assetRecordName = pair.assetRecordName,
        )))

        assertEquals(
            listOf(DisplayLink("icloud:$NAMESPACE:${pair.assetRecordName}", phone.id, ACCOUNT)),
            database.catalogDao().observeDisplayLinks().first(),
        )
    }

    @Test
    fun aLargerOriginalIsNoMatchEvenWhenTheTransportWrapsTheRefusal() = runBlocking {
        phoneItem(1, "IMG_0001.HEIC")
        syncedCatalog()
        libraryItem("one", "IMG_0001.HEIC", bytes(1) + ByteArray(64), capturedAt = CAPTURED, listedSize = bytes(1).size.toLong())
        protocol.wrapWriteErrors = true
        val photos = enrolled()

        photos.syncNow()

        assertFalse(photos.single().alreadyInICloud)
        assertEquals("Decided once, then uploaded, not retried as a failed read", 1, protocol.uploadStarts)
        assertEquals(1, protocol.downloads.size)
        assertEquals(ExistingCopyCheck.RESULT_NOT_FOUND, transferRow().existingCopyCheck)
    }

    @Test
    fun aDownloadCutShortThatIsAPrefixOfThePhoneFileIsNoMatch() = runBlocking {
        phoneItem(1, "IMG_0001.HEIC")
        syncedCatalog()
        libraryItem("one", "IMG_0001.HEIC", bytes(1), capturedAt = CAPTURED)
        protocol.truncatedTo["asset-one"] = bytes(1).size - 1
        val photos = enrolled()

        photos.syncNow()

        assertFalse(photos.single().alreadyInICloud)
        assertEquals(1, protocol.uploadStarts)
    }

    @Test
    fun anOriginalThatChangesBetweenLookupAndDownloadIsNoMatch() = runBlocking {
        phoneItem(1, "IMG_0001.HEIC")
        syncedCatalog()
        libraryItem("one", "IMG_0001.HEIC", bytes(1), capturedAt = CAPTURED)
        protocol.identityChanges += "asset-one"
        val photos = enrolled()

        photos.syncNow()

        assertFalse(photos.single().alreadyInICloud)
        assertEquals(1, protocol.uploadStarts)
    }

    @Test
    fun anExpungedLibraryItemIsNeverOffered() = runBlocking {
        phoneItem(1, "IMG_0001.HEIC")
        syncedCatalog()
        libraryItem("one", "IMG_0001.HEIC", bytes(1), capturedAt = CAPTURED)
        protocol.expunged += "asset-one"
        val photos = enrolled()

        photos.syncNow()

        assertFalse(photos.single().alreadyInICloud)
        assertEquals(1, protocol.uploadStarts)
        assertTrue(protocol.downloads.isEmpty())
    }

    @Test
    fun theLeaseIsExtendedForEachDownload() = runBlocking {
        phoneItem(1, "IMG_0001.HEIC")
        syncedCatalog()
        libraryItem("one", "IMG_0001.HEIC", bytes(1), capturedAt = CAPTURED)
        var leasedUntil: Long? = null
        protocol.onStream = { leasedUntil = transferRow().leaseExpiresAtEpochMillis }
        val photos = enrolled()

        photos.syncNow()

        assertEquals(NOW + ExistingCopyCheck.COMPARE_LEASE_MILLIS, leasedUntil)
        assertTrue(photos.single().alreadyInICloud)
    }

    @Test
    fun aLeaseLostDuringTheDownloadLinksNothingAndLeavesTheTransferToItsHolder() = runBlocking {
        val phone = phoneItem(1, "IMG_0001.HEIC")
        syncedCatalog()
        libraryItem("one", "IMG_0001.HEIC", bytes(1), capturedAt = CAPTURED)
        protocol.onStream = { database.openHelper.writableDatabase.execSQL("UPDATE transfers SET leaseToken = 'another-pass'") }
        val photos = enrolled()

        photos.syncNow()

        val row = transferRow()
        assertEquals(TransferState.READY, row.state)
        assertEquals("another-pass", row.leaseToken)
        assertNull(row.existingCopyCheck)
        assertNull(database.verificationDao().findCurrentProof(phone.id, ACCOUNT))
        assertEquals(0, protocol.uploadStarts)
    }

    @Test
    fun theFailureWindowRunsFromTheFirstFailedReadNotFromWhenTheItemWasQueued() = runBlocking {
        phoneItem(1, "IMG_0001.HEIC")
        val photos = enrolled()
        photos.syncNow()
        assertEquals("Queued while the library is unread", StoredMessage.CHECKING_ICLOUD_LIBRARY_FIRST.code, photos.single().message)

        clock += 2 * ExistingCopyCheck.CHECK_FAILURE_WINDOW_MILLIS
        syncedCatalog(refreshedAt = clock)
        libraryItem("one", "IMG_0001.HEIC", bytes(1), capturedAt = CAPTURED)
        protocol.failDownloads = true
        photos.syncNow()

        assertEquals("One failed read of an item queued long ago does not settle it", 0, protocol.uploadStarts)
        assertEquals(clock, transferRow().existingCopyFirstFailureAtEpochMillis)

        protocol.failDownloads = false
        clock += ExistingCopyCheck.CHECK_RETRY_MILLIS
        photos.syncNow()

        assertTrue(photos.single().alreadyInICloud)
        assertEquals(0, protocol.uploadStarts)
    }

    @Test
    fun theUsersOwnUploadIsNeverSkipped() = runBlocking {
        val phone = phoneItem(1, "IMG_0001.HEIC")
        syncedCatalog()
        libraryItem("one", "IMG_0001.HEIC", bytes(1), capturedAt = CAPTURED)
        database.catalogDao().upsertMetadata(listOf(deviceRow(phone)))
        val photos = companion()

        photos.uploadDeviceMedia(phone.id)
        photos.runUserUploads()

        assertEquals(1, protocol.uploadStarts)
        assertTrue("The user's own upload is not looked up in the library", protocol.lookups.isEmpty())
    }

    private suspend fun DefaultPhotoCompanion.single(): TransferView = observeTransfers().first().single()

    private suspend fun transferRow(): TransferEntity = database.transferDao()
        .findTransfer(database.transferDao().observeTransfers().first().single().transferId)!!

    private suspend fun enrolled(photos: DefaultPhotoCompanion = companion()): DefaultPhotoCompanion {
        val draft = photos.beginBackupEnrollment(BackupScope.CAMERA_MEDIA)
        photos.acceptBackupDisclosure(draft.id)
        photos.finishBackupEnrollment(draft.id)
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
        elapsedRealtime = { clock },
        cloudLibrary = CatalogLibraryIndex(database, { NAMESPACE }, { catalogAccount }, { refreshRequests += 1 }, { clock }),
    )

    private suspend fun phoneItem(id: Long, name: String): DeviceMediaRecord {
        val item = DeviceMediaRecord(
            id = "device:external:$id", fileName = name, capturedAtEpochMillis = CAPTURED, width = 10, height = 10,
            contentUri = "content://media/external/images/media/$id", sourceRevision = "rev:$id", volumeName = "external",
            volumeVersion = "v1", mediaStoreId = id, generationModified = id, mimeType = "image/jpeg",
            byteCount = bytes(id).size.toLong(),
        )
        source.items += item
        source.dateTaken[item.contentUri] = CAPTURED
        database.catalogDao().upsertMetadata(listOf(deviceRow(item)))
        return item
    }

    private fun deviceRow(item: DeviceMediaRecord) = CatalogItemEntity(
        mediaId = item.id, fileName = item.fileName, capturedAtEpochMillis = item.capturedAtEpochMillis, width = 10, height = 10,
        origin = "DEVICE", sourceRevision = item.sourceRevision, localUri = item.contentUri, previewCachePath = null,
        originalCachePath = null, accentStartArgb = 0, accentEndArgb = 0, lastSeenScanId = "scan", byteCount = item.byteCount,
    )

    private suspend fun syncedCatalog(refreshedAt: Long = NOW) {
        database.catalogDao().upsertCheckpoint(CatalogCheckpointEntity("icloud:$NAMESPACE", "token", refreshedAt))
    }

    private suspend fun libraryItem(name: String, fileName: String, original: ByteArray, capturedAt: Long, listedSize: Long = original.size.toLong()) {
        protocol.library["asset-$name"] = original
        database.catalogDao().upsertMetadata(listOf(CatalogItemEntity(
            mediaId = "icloud:$NAMESPACE:asset-$name", fileName = fileName, capturedAtEpochMillis = capturedAt, width = 10,
            height = 10, origin = "ICLOUD", sourceRevision = "tag:tag", localUri = null, previewCachePath = null,
            originalCachePath = null, accentStartArgb = 0, accentEndArgb = 0, lastSeenScanId = "scan",
            masterRecordName = "master-$name", assetRecordName = "asset-$name", byteCount = listedSize,
        )))
    }

    private class FakeSource(private val context: Context) : ExactOriginalSource {
        val items = mutableListOf<DeviceMediaRecord>()
        val dateTaken = mutableMapOf<String, Long>()

        override fun permissionFingerprint() = "full:unredacted:test"
        override fun hasAutomaticBackupAccess() = true
        override suspend fun captureEnrollmentBaseline() = LocalDiscoveryCheckpoint("external", "v1", 0, Long.MAX_VALUE)

        override suspend fun discoverEligibleMedia(
            checkpoint: LocalDiscoveryCheckpoint?, limit: Int, scope: BackupScope, folders: Set<String>,
            addedAfterEpochMillis: Long, enrollmentBaseline: LocalDiscoveryCheckpoint?,
        ): LocalDiscoveryPage {
            val after = checkpoint?.generationModified ?: -1L
            val page = items.filter { it.generationModified > after }.take(limit)
            return LocalDiscoveryPage(
                page,
                LocalDiscoveryCheckpoint("external", "v1", page.lastOrNull()?.generationModified ?: after, page.lastOrNull()?.mediaStoreId ?: -1),
                false,
            )
        }

        override suspend fun discoverExistingMedia(
            cursor: ExistingMediaCursor?, limit: Int, scope: BackupScope, folders: Set<String>,
            addedAfterEpochMillis: Long, enrollmentBaseline: LocalDiscoveryCheckpoint?,
        ) = ExistingMediaPage(emptyList(), false)

        override suspend fun eligibleMediaIds(
            scope: BackupScope, folders: Set<String>, addedAfterEpochMillis: Long, enrollmentBaseline: LocalDiscoveryCheckpoint?,
            includeExisting: Boolean,
        ) = items.map { it.id }.toSet()

        override suspend fun copySelected(contentUri: String, displayNameHint: String?, output: OutputStream) =
            evidence(contentUri, output)

        override suspend fun copyMediaStoreItem(contentUri: String, expectedRevision: String, output: OutputStream) =
            evidence(contentUri, output)

        override suspend fun hashMediaStoreItem(contentUri: String, expectedRevision: String) = evidence(contentUri, null)

        override suspend fun currentRevision(contentUri: String) = items.first { it.contentUri == contentUri }.sourceRevision

        override fun createTrashRequest(contentUris: List<String>): IntentSender = PendingIntent.getActivity(
            context, 45, Intent("me.lvca.alba.TEST_TRASH").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE,
        ).intentSender

        override suspend fun readTrashState(contentUri: String): LocalTrashState? = LocalTrashState(true, null)

        private fun evidence(contentUri: String, output: OutputStream?): ExactOriginalEvidence {
            val item = items.first { it.contentUri == contentUri }
            val bytes = bytes(item.mediaStoreId)
            output?.write(bytes)
            return ExactOriginalEvidence(
                contentUri = contentUri, displayName = item.fileName, mimeType = "image/jpeg", lineageKey = "lineage:$contentUri",
                // MediaStore gives the staged copy the same revision the phone scan recorded.
                sourceRevision = item.sourceRevision, acquisitionMethod = "TEST", originalFormatRequested = true, unredacted = true,
                formatValidated = true, byteCount = bytes.size.toLong(), sha256Hex = sha256(bytes),
                lastModifiedAtEpochMillis = MODIFIED_AT, dateTakenEpochMillis = dateTaken[contentUri],
            )
        }
    }

    /** An iCloud library of [library] originals by asset record name; uploads show back as one exact copy. */
    private class FakeProtocol : ICloudPhotoProtocol {
        val library = mutableMapOf<String, ByteArray>()
        val recentlyDeleted = mutableSetOf<String>()

        /** Assets Photos expunged; like Recently Deleted, the protocol never offers them. */
        val expunged = mutableSetOf<String>()

        /** Wraps the sink's refusals the way a transport may, so the check cannot rely on the exception's type. */
        var wrapWriteErrors = false

        /** Assets whose download ends early, after this many bytes, as a cut connection would. */
        val truncatedTo = mutableMapOf<String, Int>()

        /** Assets whose original changes between the fresh lookup and the download. */
        val identityChanges = mutableSetOf<String>()

        /** Runs as each library original starts to download. */
        var onStream: suspend () -> Unit = {}
        val lookups = mutableListOf<String>()
        val downloads = mutableListOf<String>()
        var bytesStreamed = 0
        var failDownloads = false
        var uploadStarts = 0
        private val uploads = mutableMapOf<String, ByteArray>()

        fun pairFor(name: String) = RemotePairCandidate(
            RemoteRecordRef("master-$name", "tag"), RemoteRecordRef("asset-$name", "tag"),
            relationIsCurrent = true, masterDeleted = false, assetDeleted = false, originalResourceIdentity = "resource:asset-$name",
        )

        override suspend fun canResume(binding: AccountBinding, attemptId: String) = true
        override fun currentDestination() = ICloudDestination(ACCOUNT, "session", "test@example.com")
        override suspend fun captureChangeToken(binding: AccountBinding) = "before"

        override suspend fun libraryPair(binding: AccountBinding, masterRecordName: String, assetRecordName: String): RemotePairCandidate? {
            lookups += assetRecordName
            if (assetRecordName !in library || assetRecordName in recentlyDeleted || assetRecordName in expunged) return null
            return pairFor(assetRecordName.removePrefix("asset-"))
        }

        override suspend fun prepareOriginal(
            binding: AccountBinding, fileName: String, source: OneShotUploadSource, lastModifiedAtEpochMillis: Long?,
            captureOffsetMinutes: Int?,
        ): PreparedICloudUpload = object : PreparedICloudUpload {
            override suspend fun startOnce(attemptId: String): UploadAcceptance {
                uploadStarts += 1
                uploads[attemptId] = source.openOnce().use { it.readBytes() }
                return UploadAcceptance("request", "master:$attemptId", "asset:$attemptId", false)
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
            library[pair.asset.name]?.let { original ->
                downloads += pair.asset.name
                onStream()
                if (failDownloads) throw java.io.IOException("connection reset")
                val sent = original.copyOf(truncatedTo[pair.asset.name] ?: original.size)
                try {
                    sent.forEach { byte -> output.write(byte.toInt()); bytesStreamed += 1 }
                } catch (refused: java.io.IOException) {
                    if (wrapWriteErrors) throw java.io.IOException("transport failed while streaming", refused) else throw refused
                }
                val identity = if (pair.asset.name in identityChanges) "changed" else pair.originalResourceIdentity
                // Reports the full size even when cut short: only what actually arrived may count.
                return RemoteOriginalObservation(identity, original.size.toLong())
            }
            val bytes = uploads.getValue(pair.master.name.removePrefix("master:"))
            output.write(bytes)
            return RemoteOriginalObservation("resource", bytes.size.toLong())
        }
    }

    private object NoOpScheduler : WakeupScheduler {
        override suspend fun scheduleSoon() = Unit
        override suspend fun setAutomaticBackupEnabled(enabled: Boolean) = Unit
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val CAPTURED = 1_700_000_000_000L
        const val MODIFIED_AT = 1_700_000_100_000L
        const val ACCOUNT = "account"
        const val NAMESPACE = "ns"
        val BINDING = AccountBinding(ACCOUNT, 1, 1)

        fun bytes(id: Long) = byteArrayOf(-1, -40, -1, 1, 2, 3, id.toByte())

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { "%02x".format(it) }
    }
}
