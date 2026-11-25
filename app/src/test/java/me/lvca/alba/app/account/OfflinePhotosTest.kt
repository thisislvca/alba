package me.lvca.alba.app.account

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.lvca.alba.app.platform.NetworkStatus
import me.lvca.alba.app.work.OfflinePhotosScheduler
import me.lvca.alba.engine.model.CacheLimit
import me.lvca.alba.engine.model.OfflineThumbnailProgress
import me.lvca.alba.engine.model.ThumbnailBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The two storage choices, and keeping the offline set filling. */
class OfflinePhotosTest {
    private val events = mutableListOf<String>()
    private val budget = MutableStateFlow(ThumbnailBudget.GB_2)
    private var ready = true
    private val scheduler = object : OfflinePhotosScheduler {
        override fun schedule(replace: Boolean) { events += if (replace) "replace" else "schedule" }
        override fun cancel() { events += "cancel" }
    }
    private val offline = OfflinePhotos(
        budget = budget,
        cacheLimit = MutableStateFlow(CacheLimit.DEFAULT),
        saveBudget = { budget.value = it; events += "save ${it.name}" },
        saveCacheLimit = { events += "save ${it.name}" },
        scheduler = scheduler,
        stopFill = { events += "stop" },
        applyLimits = { events += "trim" },
        accountReady = { ready },
    )

    @Test fun aSmallerBudgetStopsTheFillThenTrimsOnceThenFillsTheNewSet() = runBlocking {
        offline.chooseBudget(ThumbnailBudget.GB_1)
        assertEquals(listOf("save GB_1", "stop", "trim", "replace"), events)
    }

    @Test fun aLargerBudgetOnlyGrowsTheSet() = runBlocking {
        offline.chooseBudget(ThumbnailBudget.NO_LIMIT)
        assertEquals(listOf("save NO_LIMIT", "trim", "replace"), events)
    }

    @Test fun signedOutABudgetChangeSchedulesNothing() = runBlocking {
        ready = false
        offline.chooseBudget(ThumbnailBudget.GB_4)
        assertEquals(listOf("save GB_4", "trim"), events)
    }

    @Test fun theCacheLimitTrimsOnceAndLeavesTheFillAlone() = runBlocking {
        offline.chooseCacheLimit(CacheLimit.MB_250)
        assertEquals(listOf("save MB_250", "trim"), events)
    }

    @Test fun theFillIsScheduledWhileTheSetIsIncompleteAndAgainWhenPhotosArrive() = runBlocking {
        val signedIn = MutableStateFlow(false)
        val set = MutableStateFlow(OfflineThumbnailProgress(libraryCount = 100, setSize = 100, savedCount = 40))
        val progress: (ThumbnailBudget) -> Flow<OfflineThumbnailProgress> = { budget -> set.map { it.copy(setSize = minOf(it.libraryCount, budget.capacity)) } }
        val job = offline.keepFilling(this, signedIn, progress)
        suspend fun settle() = repeat(10) { yield() }

        settle()
        // Not signed in: nothing.
        assertEquals(emptyList<String>(), events)
        signedIn.value = true; settle()
        assertEquals(listOf("schedule"), events)
        // Saved thumbnails do not schedule it again.
        set.value = set.value.copy(savedCount = 60); settle()
        assertEquals(listOf("schedule"), events)
        // New photos do.
        set.value = set.value.copy(libraryCount = 102); settle()
        assertEquals(listOf("schedule", "schedule"), events)
        // A complete set does not.
        set.value = OfflineThumbnailProgress(libraryCount = 102, setSize = 102, savedCount = 102); settle()
        assertEquals(listOf("schedule", "schedule"), events)
        job.cancelAndJoin()
    }

    @Test fun theStatusSaysWhyTheFillWaits() {
        val incomplete = OfflineThumbnailProgress(libraryCount = 12_606, setSize = 12_606, savedCount = 8_400)
        val wifi = NetworkStatus(online = true, metered = false)
        val mobile = NetworkStatus(online = true, metered = true)

        assertEquals(OfflinePhotosWait.WIFI_AND_CHARGING, offlinePhotosStatus(ThumbnailBudget.GB_1, incomplete, false, mobile, true).wait)
        assertEquals(OfflinePhotosWait.WIFI_AND_CHARGING, offlinePhotosStatus(ThumbnailBudget.GB_1, incomplete, false, wifi, false).wait)
        assertNull(offlinePhotosStatus(ThumbnailBudget.GB_1, incomplete, false, wifi, true).wait)
        assertEquals(OfflinePhotosWait.STORAGE_LOW, offlinePhotosStatus(ThumbnailBudget.GB_1, incomplete, true, wifi, true).wait)
        val complete = incomplete.copy(savedCount = 12_606)
        assertNull(offlinePhotosStatus(ThumbnailBudget.GB_1, complete, true, mobile, false).wait)
    }

    @Test fun aBudgetTooSmallForTheLibrarySuggestsTheSmallestThatFits() {
        // 1 GB holds 15,873 photos at 63 KB each.
        val large = OfflineThumbnailProgress(libraryCount = 22_000, setSize = 15_873, savedCount = 15_873)
        val status = offlinePhotosStatus(ThumbnailBudget.GB_1, large, false, null, null)
        assertEquals(ThumbnailBudget.GB_2, status.suggestedBudget)
        assertEquals(22_000 * 63_000L, status.libraryBytes)
        assertNull(offlinePhotosStatus(ThumbnailBudget.GB_2, large, false, null, null).suggestedBudget)
        val huge = large.copy(libraryCount = 80_000)
        assertEquals(ThumbnailBudget.NO_LIMIT, offlinePhotosStatus(ThumbnailBudget.GB_1, huge, false, null, null).suggestedBudget)
        // The owner's 12,606 photos fit the default.
        assertNull(offlinePhotosStatus(ThumbnailBudget.GB_1, large.copy(libraryCount = 12_606), false, null, null).suggestedBudget)
    }
}
