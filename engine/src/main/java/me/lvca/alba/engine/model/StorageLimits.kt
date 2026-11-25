package me.lvca.alba.engine.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Decimal, as Android shows file sizes, so 500 MB reads as 500 MB. */
private const val MEGABYTE = 1_000L * 1_000L
private const val BYTES_250_MB = 250L * MEGABYTE
private const val BYTES_500_MB = 500L * MEGABYTE
private const val BYTES_1_GB = 1_000L * MEGABYTE
private const val BYTES_2_GB = 2_000L * MEGABYTE
private const val BYTES_4_GB = 4_000L * MEGABYTE

/**
 * Room for the library's thumbnails, kept so every photo shows offline. The budget holds the newest photos by capture
 * date, [THUMBNAIL_ALLOWANCE_BYTES] each, so the set it keeps is fixed: it changes only when photos arrive or the
 * budget does, and nothing in it is ever swapped out for something browsed. [bytes] is null for [NO_LIMIT].
 * Stored by name: keep the names.
 */
enum class ThumbnailBudget(val bytes: Long?) {
    GB_1(BYTES_1_GB),
    GB_2(BYTES_2_GB),
    GB_4(BYTES_4_GB),
    NO_LIMIT(null),
    ;

    /** How many of the newest photos this budget keeps thumbnails for. */
    val capacity: Int get() = bytes?.let { (it / THUMBNAIL_ALLOWANCE_BYTES).toInt() } ?: Int.MAX_VALUE

    companion object {
        val DEFAULT = GB_1

        /**
         * What one grid thumbnail is counted at: iCloud's 360 by 480 JPEG averages about this, so 1 GB holds about
         * 16,000 photos. A fixed figure keeps the set from shifting as real sizes are measured.
         */
        const val THUMBNAIL_ALLOWANCE_BYTES = 63_000L

        /** The smallest budget that keeps a thumbnail for each of [photos], or [NO_LIMIT]. */
        fun fitting(photos: Int): ThumbnailBudget = entries.first { it.capacity >= photos }
    }
}

/**
 * Room for everything else Alba keeps to show photos quickly: full-size images, and thumbnails of photos outside the
 * offline set. What was opened or scrolled to longest ago goes first. Offline originals the user chose to keep are
 * not part of it. Stored by name: keep the names.
 */
enum class CacheLimit(val bytes: Long) {
    MB_250(BYTES_250_MB),
    MB_500(BYTES_500_MB),
    GB_1(BYTES_1_GB),
    GB_2(BYTES_2_GB),
    ;

    companion object {
        val DEFAULT = MB_500
    }
}

/** The offline thumbnail set: the library's photos, how many of them fit the budget, and how many of those are saved. */
data class OfflineThumbnailProgress(val libraryCount: Int = 0, val setSize: Int = 0, val savedCount: Int = 0) {
    val complete: Boolean get() = savedCount >= setSize
}

/**
 * Filling the offline thumbnail set in the background. The set is the newest photos that fit the budget, and what is
 * left to save is read from the catalog each time, so there is no cursor to drift.
 */
interface OfflineThumbnails {
    fun observeOfflineThumbnails(budget: ThumbnailBudget): Flow<OfflineThumbnailProgress> = flowOf(OfflineThumbnailProgress())

    /** Up to [limit] photos in the offline set without a saved thumbnail, newest first, leaving out [skip]. */
    suspend fun missingOfflineThumbnails(limit: Int, skip: Collection<String>): List<String> = emptyList()

    /** Fetches fresh download links for these photos in one request, where the source can. */
    suspend fun prepareDownloads(mediaIds: List<String>) = Unit

    /**
     * Saves one thumbnail of the offline set through the grid's own download. It does not count as seen, and nothing is
     * removed to make room for it.
     */
    suspend fun saveOfflineThumbnail(mediaId: String) = Unit

    /**
     * Whether background filling may start its next download now. False while a library refresh runs, or a thumbnail or
     * full-size image is wanted on screen: the fill waits, so they never queue behind it.
     */
    fun backgroundTurn(): Boolean = true

    /** Free space left before the low-storage reserve, which background filling never eats into. */
    suspend fun offlineThumbnailRoom(): Long = Long.MAX_VALUE
}
