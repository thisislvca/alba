package me.lvca.alba.engine.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The two budgets: the offline set's thumbnails, never swapped out for anything, and the cache of full-size images and
 * thumbnails outside the set, trimmed by recency. Low storage empties the cache first, then the set's oldest photos.
 */
@RunWith(AndroidJUnit4::class)
class StorageBudgetsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var limit = 1_000L
    private var available = Long.MAX_VALUE
    private var now = 100_000L
    private val cache = MediaFileCache(context, availableBytes = { available }, cacheLimitBytes = { limit }, clock = { now })
        .also { runBlocking { it.clearAll() } }

    @After fun tearDown() = runBlocking { cache.clearAll(); Unit }

    /** Writes a [size]-byte thumbnail for [id] last used at [usedAt]. */
    private fun thumbnail(id: String, usedAt: Long, size: Int = 300): File = write(id, "preview", usedAt, size)

    private fun viewer(id: String, usedAt: Long, size: Int = 300): File = write(id, "viewer", usedAt, size)

    private fun write(id: String, variant: String, usedAt: Long, size: Int): File = runBlocking {
        cache.writeAtomically(id, variant) { it.write(ByteArray(size)) }.also { it.setLastModified(usedAt) }
    }

    private fun usage() = runBlocking { cache.usage() }

    @Test fun theCacheTrimsWhatWasUsedLongestAgoAndNeverTheOfflineSet() = runBlocking {
        val newest = thumbnail("newest", usedAt = 1_000)
        val older = thumbnail("older", usedAt = 2_000)
        val outside = thumbnail("outside", usedAt = 3_000)
        val openedLongAgo = viewer("opened-long-ago", usedAt = 4_000)
        val openedRecently = viewer("opened-recently", usedAt = 9_000)
        val scrolledTo = thumbnail("scrolled-to", usedAt = 5_000)
        cache.setOfflineThumbnails(listOf("newest", "older"))
        // The set is 600 bytes, and is not counted: the cache holds 1,200 against a 700 limit, and trims to 630.
        limit = 700L
        cache.notePreviewsShown(listOf("scrolled-to"))
        cache.notePreviewsShown(emptyList())

        val evicted = cache.trimCache().evictedPaths

        assertEquals(setOf(outside.path, openedLongAgo.path), evicted)
        assertTrue(newest.isFile && older.isFile && openedRecently.isFile && scrolledTo.isFile)
        assertEquals(600L, usage().cacheBytes)
        assertEquals(600L, usage().offlineThumbnailBytes)
    }

    @Test fun thumbnailsOutsideTheSetAndViewersShareOneLimit() = runBlocking {
        cache.setOfflineThumbnails(listOf("in-set"))
        thumbnail("in-set", usedAt = 1_000, size = 5_000)
        thumbnail("outside", usedAt = 2_000)
        viewer("viewer", usedAt = 3_000)
        // However large the set, the cache is what it holds outside it.
        assertFalse(cache.needsTrim())
        viewer("another", usedAt = 4_000, size = 500)
        assertTrue(cache.needsTrim())
    }

    @Test fun aPhotoThatLeavesTheSetJoinsTheCache() = runBlocking {
        cache.setOfflineThumbnails(listOf("a", "b", "c"))
        val a = thumbnail("a", usedAt = 3_000)
        thumbnail("b", usedAt = 2_000)
        val c = thumbnail("c", usedAt = 1_000)
        limit = 200L
        assertTrue(cache.trimCache().evictedPaths.isEmpty())

        // Two newer photos arrived and the budget holds three: "b" and "c" drop out, and the cache trims them by use.
        cache.setOfflineThumbnails(listOf("new-1", "new-2", "a"))
        val evicted = cache.trimCache().evictedPaths

        assertTrue(c.path in evicted)
        assertTrue(a.isFile)
    }

    @Test fun backgroundSavesNeverOutrankWhatTheUserLookedAt() = runBlocking {
        val seen = thumbnail("seen", usedAt = 5_000)
        val saved = thumbnail("saved-in-background", usedAt = now)
        cache.markUnseen("saved-in-background", saved)
        // Both drop out of the set; the cache has room for one.
        cache.setOfflineThumbnails(emptyList())
        limit = 400L

        assertEquals(setOf(saved.path), cache.trimCache().evictedPaths)
        assertTrue(seen.isFile)
    }

    @Test fun aBackgroundSaveOfAPhotoOnScreenKeepsItsUse() = runBlocking {
        cache.notePreviewsShown(listOf("on-screen"))
        val file = thumbnail("on-screen", usedAt = 50_000)
        cache.markUnseen("on-screen", file)
        assertEquals(50_000L, file.lastModified())
    }

    @Test fun reopeningAFullSizeImageCountsAsUse() = runBlocking {
        cache.setOfflineThumbnails(emptyList())
        val first = viewer("first", usedAt = 1_000)
        val second = viewer("second", usedAt = 2_000)
        now = 10_000L
        cache.noteUsed(first.path)
        limit = 400L

        assertEquals(setOf(second.path), cache.trimCache().evictedPaths)
    }

    @Test fun lowStorageEmptiesTheCacheFirstThenTheOldestPhotosOfTheSet() = runBlocking {
        val newest = thumbnail("newest", usedAt = 1_000)
        val middle = thumbnail("middle", usedAt = 1_000)
        val oldest = thumbnail("oldest", usedAt = 9_000)
        val outside = thumbnail("outside", usedAt = 9_500)
        val opened = viewer("opened", usedAt = 9_900)
        cache.setOfflineThumbnails(listOf("newest", "middle", "oldest"))
        // 1,000 bytes short of the free space Alba keeps, with the cache well within its limit.
        limit = 10_000L
        available = MediaFileCache.LOW_STORAGE_FREE_BYTES - 1_000

        assertTrue(cache.needsTrim())
        val evicted = cache.trimCache().evictedPaths

        // The cache goes whatever its use; then the set by capture date, never by when it was seen.
        assertEquals(setOf(outside.path, opened.path, oldest.path, middle.path), evicted)
        assertTrue(newest.isFile)
    }

    @Test fun lowStorageLeavesWhatIsOnScreen() = runBlocking {
        val newest = thumbnail("newest", usedAt = 1_000)
        val oldest = thumbnail("oldest", usedAt = 1_000)
        cache.setOfflineThumbnails(listOf("newest", "oldest"))
        cache.notePreviewsShown(listOf("oldest"))
        available = MediaFileCache.LOW_STORAGE_FREE_BYTES - 1_000

        assertEquals(setOf(newest.path), cache.trimCache().evictedPaths)
        assertTrue(oldest.isFile)
    }

    @Test fun theRoomBeforeTheReserveIsWhatTheBackgroundFillMayUse() = runBlocking {
        available = MediaFileCache.LOW_STORAGE_FREE_BYTES + 5_000
        assertEquals(5_000L, cache.roomBeforeReserve())
    }

    @Test fun untilTheSetIsNamedEveryThumbnailStays() = runBlocking {
        val thumbnail = thumbnail("any", usedAt = 1_000)
        limit = 100L
        assertTrue(cache.trimCache().evictedPaths.isEmpty())
        assertTrue(thumbnail.isFile)
    }
}
