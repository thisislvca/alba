package me.lvca.alba.app.account

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import me.lvca.alba.app.platform.NetworkStatus
import me.lvca.alba.app.work.OfflinePhotosScheduler
import me.lvca.alba.engine.model.CacheLimit
import me.lvca.alba.engine.model.OfflineThumbnailProgress
import me.lvca.alba.engine.model.ThumbnailBudget

/** Why the offline set is not filling right now. */
enum class OfflinePhotosWait {
    /** The fill runs only on Wi-Fi while charging. */
    WIFI_AND_CHARGING,

    /** The fill stopped short of the low-storage reserve. */
    STORAGE_LOW,
}

/** "8,400 of 12,606 photos available offline", why it is waiting if it is, and the budget that would keep them all. */
data class OfflinePhotosStatus(
    val progress: OfflineThumbnailProgress,
    val wait: OfflinePhotosWait? = null,
    /** A larger budget that keeps every photo, when the current one is too small; null when it is enough. */
    val suggestedBudget: ThumbnailBudget? = null,
) {
    /** About what thumbnails of the whole library take, at the budget's allowance per photo. */
    val libraryBytes: Long get() = progress.libraryCount * ThumbnailBudget.THUMBNAIL_ALLOWANCE_BYTES
}

/** The status the settings show for [budget] from the set's [progress] and the phone's state. */
internal fun offlinePhotosStatus(
    budget: ThumbnailBudget,
    progress: OfflineThumbnailProgress,
    storageLow: Boolean,
    network: NetworkStatus?,
    charging: Boolean?,
): OfflinePhotosStatus {
    val canFill = network?.online == true && !network.metered && charging == true
    val wait = when {
        progress.complete -> null
        storageLow -> OfflinePhotosWait.STORAGE_LOW
        !canFill -> OfflinePhotosWait.WIFI_AND_CHARGING
        else -> null
    }
    val suggestion = ThumbnailBudget.fitting(progress.libraryCount).takeIf { budget.capacity < progress.libraryCount }
    return OfflinePhotosStatus(progress, wait, suggestion)
}

/**
 * Keeps the offline set filling and applies the two storage choices. The fill is scheduled whenever a signed-in
 * library has thumbnails of the set left to save: at start, after signing in again, and when photos arrive.
 */
internal class OfflinePhotos(
    val budget: StateFlow<ThumbnailBudget>,
    val cacheLimit: StateFlow<CacheLimit>,
    private val saveBudget: (ThumbnailBudget) -> Unit,
    private val saveCacheLimit: (CacheLimit) -> Unit,
    private val scheduler: OfflinePhotosScheduler,
    /** Stops a fill in progress and waits for it to end. */
    private val stopFill: suspend () -> Unit,
    /** Recomputes the offline set and trims the cache once. */
    private val applyLimits: suspend () -> Unit,
    private val accountReady: () -> Boolean,
) {
    /**
     * A smaller budget stops the fill before the set shrinks, so nothing outside the new set is downloaded, then one
     * trim moves what no longer fits into the cache; the fill starts again for the new set. A larger budget only grows
     * the set, and the fill saves what it adds.
     */
    suspend fun chooseBudget(value: ThumbnailBudget) {
        val shrinks = value.capacity < budget.value.capacity
        saveBudget(value)
        if (shrinks) stopFill()
        applyLimits()
        if (accountReady()) scheduler.schedule(replace = true)
    }

    /** One trim to the new limit; the offline set is not part of the cache. */
    suspend fun chooseCacheLimit(value: CacheLimit) {
        saveCacheLimit(value)
        applyLimits()
    }

    /** Schedules the fill whenever [ready] and the set has thumbnails left, and again when the library grows. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun keepFilling(scope: CoroutineScope, ready: Flow<Boolean>, progress: (ThumbnailBudget) -> Flow<OfflineThumbnailProgress>): Job =
        combine(ready, budget.flatMapLatest(progress)) { isReady, set ->
            // Keyed on the library's size, so photos arriving schedule it again; saved thumbnails do not.
            set.libraryCount.takeIf { isReady && !set.complete }
        }.distinctUntilChanged().filterNotNull().onEach { scheduler.schedule() }.launchIn(scope)

    fun cancel() = scheduler.cancel()
}
