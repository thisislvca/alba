package me.lvca.alba.app.work

import me.lvca.alba.engine.model.WakeSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How one background backup run keeps draining the queue, and when it asks to run in the foreground. */
class BackupDrainTest {
    private val start = 1_000_000L

    @Test
    fun aPassThatMovedTheQueueIsFollowedAtOnce() {
        val drain = BackupDrain(start, foreground = false)
        assertEquals(0L, drain.nextWait(summary(invoked = 1), start + 1_000))
    }

    @Test
    fun theRunWaitsForACheckDueSoonInsteadOfEndingBetweenItems() {
        val drain = BackupDrain(start, foreground = false)
        assertEquals(15_000L, drain.nextWait(summary(next = start + 16_000), start + 1_000))
    }

    @Test
    fun anUploadThatIsReadyButDoesNotStartBacksOffAndThenEndsTheRun() {
        // A READY upload whose iCloud cursor read fails: each pass says work is due now, and nothing moves.
        val drain = BackupDrain(start, foreground = true)
        var now = start
        val waits = generateSequence { drain.nextWait(summary(next = now), now)?.also { now += it } }.toList()
        assertEquals(listOf(15_000L, 30_000L, 60_000L, 60_000L, 60_000L, 60_000L), waits)
        assertTrue("Never a pass straight after one that moved nothing", waits.all { it > 0 })
    }

    @Test
    fun aPassThatMovesTheQueueEndsTheBackoff() {
        val drain = BackupDrain(start, foreground = true)
        assertEquals(15_000L, drain.nextWait(summary(next = start), start))
        assertEquals(30_000L, drain.nextWait(summary(next = start), start))
        assertEquals(0L, drain.nextWait(summary(invoked = 1), start))
        assertEquals(15_000L, drain.nextWait(summary(next = start), start))
    }

    @Test
    fun theRunEndsWhenNothingIsDueOrIsDueTooLate() {
        val drain = BackupDrain(start, foreground = false)
        assertNull("Nothing left", drain.nextWait(summary(), start + 1_000))
        assertNull("A backoff of minutes is left to a scheduled wake-up", drain.nextWait(summary(next = start + 10 * MINUTE), start))
    }

    @Test
    fun aBackgroundRunStartsNoPassAfterItsBudgetAndStopsInsideTheWorkManagerLimit() {
        val drain = BackupDrain(start, foreground = false)
        assertNull(drain.nextWait(summary(invoked = 1), start + 6 * MINUTE))
        assertTrue(drain.hardLimitMillis < 10 * MINUTE)
    }

    @Test
    fun aForegroundRunCanOutlastALongVideo() {
        val drain = BackupDrain(start, foreground = true)
        assertEquals(0L, drain.nextWait(summary(invoked = 1), start + 30 * MINUTE))
        assertTrue(drain.hardLimitMillis >= 2 * 60 * MINUTE)
    }

    @Test
    fun aLargeBacklogOrABigQueuedFileRunsInTheForeground() {
        assertFalse(runsInForeground(remainingItems = 2, queuedBytes = 3_000_000))
        assertTrue(runsInForeground(remainingItems = 500, queuedBytes = 0))
        assertTrue("One long video", runsInForeground(remainingItems = 1, queuedBytes = 2_000_000_000))
    }

    private fun summary(invoked: Int = 0, next: Long? = null) =
        WakeSummary(0, 0, 0, invoked, moreWorkScheduled = next != null, nextWorkAtEpochMillis = next)

    private companion object {
        const val MINUTE = 60_000L
    }
}
