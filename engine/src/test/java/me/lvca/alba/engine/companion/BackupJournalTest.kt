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
import me.lvca.alba.engine.database.CatalogItemEntity
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.AutomaticBackupPolicy
import me.lvca.alba.engine.model.BackupNetwork
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.BackupSettings
import me.lvca.alba.engine.model.CandidatePage
import me.lvca.alba.engine.model.ICloudDestination
import me.lvca.alba.engine.model.ICloudPhotoProtocol
import me.lvca.alba.engine.model.ICloudStorageFullException
import me.lvca.alba.engine.model.OneShotUploadSource
import me.lvca.alba.engine.model.PhotoCompanionException
import me.lvca.alba.engine.model.PreparedICloudUpload
import me.lvca.alba.engine.model.RemoteOriginalObservation
import me.lvca.alba.engine.model.RemotePairCandidate
import me.lvca.alba.engine.model.RemoteRecordRef
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.TransferViewState
import me.lvca.alba.engine.model.UploadAcceptance
import me.lvca.alba.engine.model.UserSelectedPhoto
import me.lvca.alba.engine.model.WakeupScheduler
import me.lvca.alba.engine.source.DeviceMediaRecord
import me.lvca.alba.engine.source.ExactOriginalEvidence
import me.lvca.alba.engine.source.ExactOriginalSource
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
    private var network = BackupNetwork.UNMETERED
    private var settings = BackupSettings()
    private val policy = object : AutomaticBackupPolicy {
        override fun isUnmetered() = network == BackupNetwork.UNMETERED
        override fun network() = network
        override fun settings() = settings
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

        photos.retryAfterStorageFull()
        photos.syncNow()
        assertEquals(2, protocol.uploadStarts)
        assertEquals(TransferViewState.VERIFIED, photos.observeTransfers().first().single().state)
        assertNull(photos.observeBackup().first().storageFullSinceEpochMillis)
    }

    @Test
    fun onlyItemsThatSentNothingCanBeRetried() = runBlocking {
        val photos = companion()
        val ticket = photos.uploadSelected(UserSelectedPhoto("content://picker/one", "one.jpg"))
        photos.cancelUserUploads()
        val canceled = photos.observeTransfers().first().single()
        assertEquals(TransferViewState.NEEDS_ATTENTION, canceled.state)
        assertTrue(canceled.retryable)

        photos.retryTransfer(ticket.id)
        val retried = photos.observeTransfers().first().single()
        assertFalse("The retry replaces the canceled row", retried.id == ticket.id)
        photos.runUserUploads()
        val verified = photos.observeTransfers().first().single()
        assertEquals(TransferViewState.VERIFIED, verified.state)
        assertFalse(verified.retryable)

        try {
            photos.retryTransfer(verified.id)
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

        val candidates = photos.freeUpSpaceCandidates()
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
    fun progressCountsVerifiedItemsAgainstEverythingBackupCovers() = runBlocking {
        source.items += item(1, "one.jpg", "image/jpeg")
        source.items += item(2, "two.jpg", "image/jpeg")
        val photos = enrolled()
        photos.syncNow()

        val progress = photos.backupProgress()
        assertEquals(1, progress.backedUp)
        assertEquals(2, progress.eligible)
    }

    private suspend fun enrolled(
        scope: BackupScope = BackupScope.CAMERA_MEDIA,
        photos: DefaultPhotoCompanion = companion(),
    ): DefaultPhotoCompanion {
        val draft = photos.beginBackupEnrollment(scope)
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
        now = { NOW },
    )

    private fun item(id: Long, name: String, mime: String, refuse: String? = null) = DeviceMediaRecord(
        id = "device:external:$id",
        fileName = name,
        capturedAtEpochMillis = NOW + id,
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
        val refusals = mutableMapOf<String, String>()
        val changed = mutableSetOf<String>()
        var lastScope: BackupScope? = null

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

        override suspend fun eligibleMediaIds(
            scope: BackupScope, folders: Set<String>, addedAfterEpochMillis: Long, enrollmentBaseline: LocalDiscoveryCheckpoint?,
        ) = items.map { it.id }.toSet()

        override suspend fun copySelected(contentUri: String, displayNameHint: String?, output: OutputStream) =
            evidence(contentUri, displayNameHint ?: "selected.jpg", output)

        override suspend fun copyMediaStoreItem(contentUri: String, expectedRevision: String, output: OutputStream): ExactOriginalEvidence {
            refusals[contentUri]?.let { throw UnsupportedUploadException(it) }
            return evidence(contentUri, items.firstOrNull { it.contentUri == contentUri }?.fileName ?: "camera.jpg", output)
        }

        override suspend fun hashMediaStoreItem(contentUri: String, expectedRevision: String) =
            evidence(contentUri, "camera.jpg", null, if (contentUri in changed) byteArrayOf(-1, -40, -1, 9) else null)

        override fun createTrashRequest(contentUris: List<String>): IntentSender = PendingIntent.getActivity(
            context, 44, Intent("me.lvca.alba.TEST_TRASH").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE,
        ).intentSender

        override suspend fun readTrashState(contentUri: String): LocalTrashState? = LocalTrashState(true, null)

        private fun evidence(contentUri: String, name: String, output: OutputStream?, override: ByteArray? = null): ExactOriginalEvidence {
            val id = items.firstOrNull { it.contentUri == contentUri }?.mediaStoreId ?: contentUri.hashCode().toLong()
            val bytes = override ?: bytes(id)
            output?.write(bytes)
            return ExactOriginalEvidence(
                contentUri = contentUri, displayName = name, mimeType = "image/jpeg", lineageKey = "lineage:$contentUri",
                sourceRevision = "rev", acquisitionMethod = "TEST", originalFormatRequested = true, unredacted = true,
                formatValidated = true, byteCount = bytes.size.toLong(), sha256Hex = sha256(bytes),
            )
        }
    }

    /** Accepts each upload and shows it back as one exact iCloud copy; can refuse reservations for lack of storage. */
    private class FakeProtocol : ICloudPhotoProtocol {
        var storageFull = false
        var uploadStarts = 0
        private val uploads = mutableMapOf<String, ByteArray>()

        override suspend fun canResume(binding: AccountBinding, attemptId: String) = true
        override fun currentDestination() = ICloudDestination("account", "session", "test@example.com")
        override suspend fun captureChangeToken(binding: AccountBinding) = "before"

        override suspend fun prepareOriginal(
            binding: AccountBinding, fileName: String, source: OneShotUploadSource, lastModifiedAtEpochMillis: Long?,
        ): PreparedICloudUpload = object : PreparedICloudUpload {
            override suspend fun startOnce(attemptId: String): UploadAcceptance {
                uploadStarts += 1
                if (storageFull) throw ICloudStorageFullException()
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
        const val NOW = 1_000_000L

        fun bytes(id: Long) = byteArrayOf(-1, -40, -1, 1, 2, 3, id.toByte())

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { "%02x".format(it) }
    }
}
