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
import me.lvca.alba.engine.model.RemotePairCandidate
import me.lvca.alba.engine.model.RemoteRecordRef
import me.lvca.alba.engine.model.TransferFailureReason
import me.lvca.alba.engine.model.UserSelectedPhoto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uploads that ended uncertain heal themselves. A NOT_OBSERVED upload is looked for again after later library
 * refreshes, by Apple's receipt, and verified when it appears; after the healing window it is sent again only when nothing
 * in iCloud could be it. An AMBIGUOUS upload is never sent again, and is settled by its bytes only when the user opens it.
 */
@RunWith(AndroidJUnit4::class)
class SelfHealingTest {
    private lateinit var context: Context
    private lateinit var database: AlbaDatabase
    private lateinit var staging: UploadStagingStore
    private val phone by lazy { TestPhone(context) }
    private val icloud = TestICloud()
    private val library = TestLibrary(icloud)
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
    fun aNotObservedUploadHealsOnALaterLibraryRefresh() = runBlocking {
        val photos = notObserved()
        assertEquals("An uncertain upload no longer holds up the queue", 0, writeSlots())
        assertEquals(TransferFailureReason.CHECKING_ICLOUD, photos.observeTransfers().first().single().failureReason)

        photos.syncNow()
        assertEquals("Nothing new to look in before the library is refreshed", TransferState.NOT_OBSERVED, state())
        assertEquals(0, library.reads)

        refresh()
        photos.syncNow()

        assertEquals("The record the change feed missed is in the refreshed library", TransferState.VERIFIED, state())
        assertEquals(VerificationProofEntity.METHOD_RECEIPT, proofMethods().single())
        assertEquals("Matched by its receipt: nothing downloaded", 0, icloud.downloads)
        assertEquals("Its bytes are no longer suppressed", 0, activeSuppressions())
        assertEquals(1, icloud.registrations)
    }

    @Test
    fun afterTheWindowAnUploadNeverObservedIsSentAgainWhenNothingInICloudCouldBeIt() = runBlocking {
        val photos = notObserved()
        // The registration never made a record: nothing in the library has these bytes, or even their size.
        icloud.registered.clear()
        icloud.feedLags = false

        refreshDailyUntil(HealSchedule.WINDOW_MILLIS - DAY, photos)
        assertEquals("Still inside the window: looked for, never sent again", TransferState.NOT_OBSERVED, state())
        assertEquals(1, icloud.registrations)

        refreshDailyUntil(HealSchedule.WINDOW_MILLIS + DAY, photos)

        assertEquals("Sent again once, and verified", 2, icloud.registrations)
        assertEquals(TransferState.VERIFIED, state())
        val attempts = database.query("SELECT predecessorAttemptId FROM upload_attempts ORDER BY authorizedAtEpochMillis", null)
            .use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
        assertEquals(2, attempts.size)
        assertTrue("The new attempt names the uncertain one", attempts.last() != null)
    }

    @Test
    fun afterTheWindowAnyCandidateInICloudMakesItAmbiguousInsteadOfASecondUpload() = runBlocking {
        val photos = notObserved()
        val sent = icloud.registered.single()
        icloud.registered.clear()
        // Something of the same size and name is in the library, but it is not these bytes and not this receipt.
        val other = otherPair("master:lookalike", sizeOf = sent)
        icloud.extraPairs += other
        library.names[other.master.name] = "IMG_1.jpg"

        refreshDailyUntil(HealSchedule.WINDOW_MILLIS + DAY, photos)

        assertEquals(TransferState.AMBIGUOUS, state())
        assertEquals("Never sent a second time", 1, icloud.registrations)
        assertEquals(1, activeSuppressions())
    }

    @Test
    fun anAmbiguousUploadIsNeverSentAgainAndIsSettledByItsBytesWhenOpened() = runBlocking {
        val photos = companion()
        val original = phone.add(1)
        val bytes = phone.bytes.getValue(original.contentUri)
        // Another client uploaded the same bytes meanwhile: two records carry the receipt.
        icloud.extraPairs += pairLike(bytes, "master:other")
        val ticket = photos.uploadSelected(UserSelectedPhoto(original.contentUri, original.fileName))
        photos.syncNow()
        assertEquals(TransferState.AMBIGUOUS, state())
        assertEquals(0, writeSlots())

        refreshDailyUntil(HealSchedule.WINDOW_MILLIS + 2 * DAY, photos)

        assertEquals(TransferState.AMBIGUOUS, state())
        assertEquals("Ambiguous uploads are never sent again", 1, icloud.registrations)
        assertEquals("Nothing is downloaded until the user opens it", 0, icloud.downloads)

        assertTrue(photos.backup.checkUncertain(ticket.id))

        assertEquals(TransferState.VERIFIED, state())
        assertEquals(VerificationProofEntity.METHOD_BYTES, proofMethods().single())
        assertEquals("Both candidates were compared", 2, icloud.downloads)
        assertEquals(0, activeSuppressions())
    }

    @Test
    fun anUncertainUploadIsLookedForAfterEachRefreshForADayThenDaily() = runBlocking {
        val photos = notObserved()
        icloud.registered.clear()
        val since = clock

        clock = since + HOUR
        refresh()
        photos.syncNow()
        assertEquals("A refresh within the first day: looked for", 1, library.reads)
        clock = since + 2 * HOUR
        refresh()
        photos.syncNow()
        assertEquals("And after the next refresh too", 2, library.reads)
        clock = since + 3 * HOUR
        photos.syncNow()
        assertEquals("No refresh since the last look: not looked for", 2, library.reads)

        clock = since + DAY + HOUR
        refresh()
        photos.syncNow()
        assertEquals("After the first day, a refresh less than a day after the last look waits", 2, library.reads)
        clock = since + DAY + 2 * HOUR
        refresh()
        photos.syncNow()
        assertEquals("A day after the last look, the next refresh is looked in", 3, library.reads)
        clock = since + DAY + 3 * HOUR
        refresh()
        photos.syncNow()
        assertEquals(3, library.reads)
        assertEquals(TransferState.NOT_OBSERVED, state())
    }

    @Test
    fun theHealScheduleLooksAfterEachRefreshForADayThenDailyAndEndsAfterEightDays() {
        val since = NOW
        assertFalse("Never refreshed", HealSchedule.isDue(since, null, null, since + HOUR))
        assertFalse("Refreshed before it became uncertain", HealSchedule.isDue(since, null, since - 1, since + HOUR))
        assertTrue(HealSchedule.isDue(since, null, since + 1, since + HOUR))
        assertTrue(HealSchedule.isDue(since, since + HOUR, since + 2 * HOUR, since + 2 * HOUR))
        assertFalse("No refresh since the last look", HealSchedule.isDue(since, since + 2 * HOUR, since + HOUR, since + 3 * HOUR))
        assertFalse("Daily after the first day", HealSchedule.isDue(since, since + 20 * HOUR, since + 30 * HOUR, since + 30 * HOUR))
        assertTrue(HealSchedule.isDue(since, since + 20 * HOUR, since + 44 * HOUR, since + 44 * HOUR))
        assertFalse(HealSchedule.windowOver(since, since + 8 * DAY - 1))
        assertTrue(HealSchedule.windowOver(since, since + 8 * DAY))
    }

    /** An upload whose record the change feed never reported by the deadline. */
    private suspend fun notObserved(): DefaultPhotoCompanion {
        val photos = companion()
        val original = phone.add(1)
        icloud.feedLags = true
        photos.uploadSelected(UserSelectedPhoto(original.contentUri, original.fileName))
        photos.syncNow()
        clock += RECONCILIATION_DEADLINE_MILLIS
        photos.syncNow()
        assertEquals(TransferState.NOT_OBSERVED, state())
        return photos
    }

    /** The library is refreshed once a day, with a pass after each, until [untilSinceStart] after the item became uncertain. */
    private suspend fun refreshDailyUntil(untilSinceStart: Long, photos: DefaultPhotoCompanion) {
        val since = database.query("SELECT unresolvedSinceEpochMillis FROM transfers", null).use { it.moveToFirst(); it.getLong(0) }
        while (clock < since + untilSinceStart) {
            clock = minOf(clock + DAY, since + untilSinceStart)
            refresh()
            photos.syncNow()
        }
    }

    /** The library is read completely now, after anything that happened before. */
    private fun refresh() {
        clock += 1
        library.refreshedAt = clock
        clock += 1
    }

    private suspend fun state() = database.transferDao().observeTransfers().first().single().state

    private fun writeSlots() = database.query("SELECT COUNT(*) FROM account_write_slots", null).use { it.moveToFirst(); it.getInt(0) }

    private fun activeSuppressions() =
        database.query("SELECT COUNT(*) FROM upload_suppressions WHERE active = 1", null).use { it.moveToFirst(); it.getInt(0) }

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
        cloudLibrary = library,
    )

    private fun pairLike(bytes: ByteArray, master: String) = RemotePairCandidate(
        RemoteRecordRef(master, "tag"), RemoteRecordRef("asset-of-$master", "tag"),
        relationIsCurrent = true, masterDeleted = false, assetDeleted = false, originalResourceIdentity = "resource:$master",
        originalByteCount = bytes.size.toLong(), originalFileChecksum = TestICloud.checksum(bytes),
        originalFingerprint = TestICloud.checksum(bytes),
    ).also { icloud.stored[master] = bytes }

    /** A library item the same size as [sizeOf] whose bytes and checksum differ. */
    private fun otherPair(master: String, sizeOf: RemotePairCandidate): RemotePairCandidate {
        val bytes = ByteArray(requireNotNull(sizeOf.originalByteCount).toInt()) { 9 }
        return RemotePairCandidate(
            RemoteRecordRef(master, "tag"), RemoteRecordRef("asset-of-$master", "tag"),
            relationIsCurrent = true, masterDeleted = false, assetDeleted = false, originalResourceIdentity = "resource:$master",
            originalByteCount = bytes.size.toLong(), originalFileChecksum = TestICloud.checksum(bytes),
            originalFingerprint = TestICloud.checksum(bytes),
        ).also { icloud.stored[master] = bytes }
    }

    private companion object {
        const val NOW = 1_000_000L
        const val HOUR = 60 * 60 * 1_000L
        const val DAY = 24 * HOUR
    }
}
