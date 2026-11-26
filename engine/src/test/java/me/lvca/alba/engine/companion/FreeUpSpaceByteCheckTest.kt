package me.lvca.alba.engine.companion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.lvca.alba.engine.cache.UploadStagingStore
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.CatalogItemEntity
import me.lvca.alba.engine.database.VerificationProofEntity
import me.lvca.alba.engine.model.AutomaticBackupPolicy
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.StoredMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Backed up by receipt is not enough to delete a phone copy. Free up space downloads each iCloud original right before
 * Android's trash request and compares every byte; only items that pass are offered, and their proof becomes a byte
 * proof. An item whose iCloud original differs stays on the phone, flagged, and is never offered again.
 */
@RunWith(AndroidJUnit4::class)
class FreeUpSpaceByteCheckTest {
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
    fun theByteCheckRunsRightBeforeDeletionAndOnlyItemsThatPassAreOffered() = runBlocking {
        val good = phone.add(1)
        val bad = phone.add(2)
        val photos = enrolled()
        repeat(2) { photos.syncNow() }
        assertEquals("Both are backed up by their receipts", listOf("RECEIPT", "RECEIPT"), proofs().map { it.method })
        assertEquals("Nothing was downloaded to back them up", 0, icloud.downloads)
        listOf(good, bad).forEach { database.catalogDao().upsertItems(listOf(deviceRow(it))) }
        assertEquals(setOf(good.id, bad.id), photos.backup.freeUpSpaceCandidates().mediaIds.toSet())

        // iCloud now serves something else for the second photo's original.
        val badMaster = icloud.registered.single { icloud.stored[it.master.name].contentEquals(phone.bytes[bad.contentUri]) }.master.name
        icloud.serveAs = { bytes -> if (bytes.contentEquals(icloud.stored[badMaster])) bytes.copyOf().also { it[it.lastIndex]++ } else bytes }

        val proposal = photos.prepareTrash(setOf(good.id, bad.id), allowPartial = true)

        assertEquals("Each item is downloaded and compared before deletion", 2, icloud.downloads)
        assertEquals(setOf(good.id), proposal.eligibleMediaIds)
        assertEquals(StoredMessage.THE_CURRENT_LOCAL_AND_ICLOUD_ORIGINALS_NO_LONGER_MATCH.code, proposal.refusals[bad.id])
        val handoff = photos.beginTrash(proposal.id)
        assertEquals("Only the item that passed goes to Android's trash request", setOf(good.id), handoff.mediaIds)

        val byMedia = proofs().associateBy { it.mediaId }
        assertEquals("A passing item's proof is upgraded", VerificationProofEntity.METHOD_BYTES, byMedia.getValue(good.id).method)
        assertEquals(NOW, byMedia.getValue(good.id).byteCheckedAtEpochMillis)
        assertNull(byMedia.getValue(good.id).invalidatedAtEpochMillis)
        assertEquals("A failing item is flagged", VerificationProofEntity.BYTE_CHECK_FAILED, byMedia.getValue(bad.id).invalidationReason)
        assertEquals("and never offered for deletion again", listOf(good.id), photos.backup.freeUpSpaceCandidates().mediaIds)
    }

    @Test
    fun anICloudReadThatFailsKeepsThePhoneCopyWithoutFlaggingIt() = runBlocking {
        val item = phone.add(1)
        val photos = enrolled()
        photos.syncNow()
        icloud.serveAs = { throw java.io.IOException("connection reset") }

        val proposal = photos.prepareTrash(setOf(item.id), allowPartial = true)

        assertEquals(emptySet<String>(), proposal.eligibleMediaIds)
        assertEquals(StoredMessage.COULD_NOT_REACH_ICLOUD_TO_CHECK.code, proposal.refusals[item.id])
        val proof = proofs().single()
        assertNull("A failed read says nothing about the copies", proof.invalidatedAtEpochMillis)
        assertEquals(VerificationProofEntity.METHOD_RECEIPT, proof.method)
    }

    private fun proofs(): List<VerificationProofEntity> = database.query("SELECT proofId FROM verification_proofs ORDER BY mediaId", null)
        .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        .map { runBlocking { requireNotNull(database.verificationDao().findProof(it)) } }

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

    private fun deviceRow(record: me.lvca.alba.engine.source.DeviceMediaRecord) = CatalogItemEntity(
        mediaId = record.id, fileName = record.fileName, capturedAtEpochMillis = record.capturedAtEpochMillis,
        width = 10, height = 10, origin = "DEVICE", sourceRevision = record.sourceRevision, localUri = record.contentUri,
        previewCachePath = null, originalCachePath = null, accentStartArgb = 0, accentEndArgb = 0, lastSeenScanId = "scan",
    )

    private companion object {
        const val NOW = 1_000_000L
    }
}
