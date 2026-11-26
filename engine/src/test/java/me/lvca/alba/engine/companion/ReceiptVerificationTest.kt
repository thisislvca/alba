package me.lvca.alba.engine.companion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.engine.cache.UploadStagingStore
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.TransferState
import me.lvca.alba.engine.database.VerificationProofEntity
import me.lvca.alba.engine.model.RemoteRecordRef
import me.lvca.alba.engine.model.TransferViewState
import me.lvca.alba.engine.model.UserSelectedPhoto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An upload is backed up when Apple's receipt for the exact bytes sent matches the new CPLMaster in iCloud's change
 * feed: same size, same checksum, and a fingerprint equal to that checksum. Nothing is downloaded on that path.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptVerificationTest {
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
    fun aReceiptMatchBacksUpWithoutDownloadingTheOriginal() = runBlocking {
        val photos = companion()
        val original = phone.add(1)
        photos.uploadSelected(UserSelectedPhoto(original.contentUri, original.fileName))

        photos.syncNow()

        assertEquals(TransferViewState.VERIFIED, photos.observeTransfers().first().single().state)
        assertEquals("The receipt is enough; the original is never downloaded", 0, icloud.downloads)
        assertEquals(VerificationProofEntity.METHOD_RECEIPT, proofMethods().single())
        val bytes = phone.bytes.getValue(original.contentUri)
        val acceptance = database.query("SELECT receiptByteCount, receiptFileChecksum, sentByteCount, sentSha256Hex FROM upload_acceptances", null)
            .use { cursor -> cursor.moveToFirst(); List(4) { cursor.getString(it) } }
        assertEquals(
            "The receipt and the hash of the bytes as they were sent are kept with the acceptance",
            listOf(bytes.size.toString(), TestICloud.checksum(bytes), bytes.size.toString(), sha256(bytes)),
            acceptance,
        )
    }

    @Test
    fun aSizeThatDiffersFromTheReceiptIsNoMatch() = runBlocking {
        icloud.recordAs = { it.copy(originalByteCount = it.originalByteCount!! + 1) }
        expectNoMatch()
    }

    @Test
    fun aChecksumThatDiffersFromTheReceiptIsNoMatch() = runBlocking {
        icloud.recordAs = { it.copy(originalFileChecksum = "apple:other") }
        expectNoMatch()
    }

    @Test
    fun aFingerprintThatDiffersFromTheReceiptIsNoMatch() = runBlocking {
        icloud.recordAs = { it.copy(originalFingerprint = "apple:other") }
        expectNoMatch()
    }

    @Test
    fun twoRecordsMatchingTheReceiptAreAmbiguousAndNeverUploadedAgain() = runBlocking {
        val photos = companion()
        val original = phone.add(1)
        val bytes = phone.bytes.getValue(original.contentUri)
        // Another client uploaded the same bytes meanwhile: its master carries the same size and checksum.
        icloud.extraPairs += icloud.pairLike(bytes, "master:other")
        photos.uploadSelected(UserSelectedPhoto(original.contentUri, original.fileName))

        photos.syncNow()

        assertEquals(TransferState.AMBIGUOUS, transferState())
        assertEquals(0, icloud.downloads)
        photos.syncNow()
        assertEquals(1, icloud.registrations)
    }

    @Test
    fun aLostRegistrationResponseIsMatchedByTheReceiptInTheJournal() = runBlocking {
        val photos = companion()
        val original = phone.add(1)
        icloud.lostResponses = 1
        photos.uploadSelected(UserSelectedPhoto(original.contentUri, original.fileName))

        photos.syncNow()

        assertEquals(TransferState.VERIFIED, transferState())
        assertEquals(0, icloud.downloads)
        assertEquals(VerificationProofEntity.METHOD_RECEIPT, proofMethods().single())
        assertEquals(1, icloud.registrations)
    }

    @Test
    fun anUploadWithNoReceiptFallsBackToTheByteCheck() = runBlocking {
        // An attempt from before receipts were kept has none: its candidates are downloaded and compared.
        icloud.receiptFor = { null }
        val photos = companion()
        val original = phone.add(1)
        photos.uploadSelected(UserSelectedPhoto(original.contentUri, original.fileName))

        photos.syncNow()

        assertEquals(TransferState.VERIFIED, transferState())
        assertEquals(1, icloud.downloads)
        assertEquals(VerificationProofEntity.METHOD_BYTES, proofMethods().single())
    }

    private suspend fun expectNoMatch() {
        val photos = companion()
        val original = phone.add(1)
        photos.uploadSelected(UserSelectedPhoto(original.contentUri, original.fileName))

        photos.syncNow()
        assertEquals("No match is not verified", TransferState.READ_FAILURE, transferState())

        clock += RECONCILIATION_DEADLINE_MILLIS
        photos.syncNow()

        assertEquals("Unmatched by the deadline goes to the existing uncertainty handling", TransferState.NOT_OBSERVED, transferState())
        assertEquals(0, proofMethods().size)
        assertEquals("Never downloaded, and never uploaded again", 0 to 1, icloud.downloads to icloud.registrations)
    }

    private suspend fun transferState() = database.transferDao().observeTransfers().first().single().state

    private fun proofMethods(): List<String> = database.query("SELECT method FROM verification_proofs", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    private fun companion() = DefaultPhotoCompanion(
        database = database,
        originals = phone,
        staging = staging,
        protocol = icloud,
        scheduler = NoWakeups,
        now = { clock },
        elapsedRealtime = { clock },
    )

    private fun TestICloud.pairLike(bytes: ByteArray, master: String) = me.lvca.alba.engine.model.RemotePairCandidate(
        RemoteRecordRef(master, "tag"), RemoteRecordRef("asset-of-$master", "tag"),
        relationIsCurrent = true, masterDeleted = false, assetDeleted = false, originalResourceIdentity = "resource:$master",
        originalByteCount = bytes.size.toLong(), originalFileChecksum = TestICloud.checksum(bytes),
        originalFingerprint = TestICloud.checksum(bytes),
    ).also { stored[master] = bytes }

    private companion object {
        const val NOW = 1_000_000L
    }
}
