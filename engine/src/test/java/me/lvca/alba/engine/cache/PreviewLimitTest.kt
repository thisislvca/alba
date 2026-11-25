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

/** Saved grid previews stay within the chosen limit, least recently shown first, and never the ones on screen. */
@RunWith(AndroidJUnit4::class)
class PreviewLimitTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var limit: Long? = 1_000L
    private var available = Long.MAX_VALUE
    private var now = 100_000L
    private val cache = MediaFileCache(context, availableBytes = { available }, previewLimitBytes = { limit }, clock = { now })
        .also { runBlocking { it.clearAll() } }

    @After fun tearDown() = runBlocking { cache.clearAll(); Unit }

    /** Writes a [size]-byte preview for [id] last used at [usedAt]. */
    private fun preview(id: String, usedAt: Long, size: Int = 300): File = runBlocking {
        cache.writeAtomically(id, "preview") { it.write(ByteArray(size)) }.also { it.setLastModified(usedAt) }
    }

    private fun previewBytes() = runBlocking { cache.usage().previewBytes }

    @Test fun evictsThePreviewsShownLongestAgoFirst() = runBlocking {
        val a = preview("a", usedAt = 1_000)
        val b = preview("b", usedAt = 2_000)
        val c = preview("c", usedAt = 3_000)
        val d = preview("d", usedAt = 4_000)
        val e = preview("e", usedAt = 5_000)
        // Written first, but shown again since: it is now the most recent.
        cache.notePreviewsShown(listOf("a"))
        cache.notePreviewsShown(emptyList())

        val evicted = cache.trimPreviews().evictedPaths

        assertEquals(setOf(b.path, c.path), evicted)
        assertTrue(a.isFile && d.isFile && e.isFile)
        assertFalse(b.exists() || c.exists())
        // Trimmed below the limit, so the next few writes do not each trim again.
        assertEquals(900L, previewBytes())
    }

    @Test fun showingPreviewsRecordsTheirUseOnTheFiles() = runBlocking {
        val a = preview("a", usedAt = 1_000)
        now = 50_000L
        cache.notePreviewsShown(listOf("a"))
        cache.flushPreviewAccess()
        assertEquals(50_000L, a.lastModified())
    }

    @Test fun neverEvictsPreviewsOnScreenOrInThePrefetchWindow() = runBlocking {
        val a = preview("a", usedAt = 1_000)
        val b = preview("b", usedAt = 2_000)
        val c = preview("c", usedAt = 3_000)
        val d = preview("d", usedAt = 4_000)
        limit = 300L
        cache.notePreviewsShown(listOf("a", "b"))

        val evicted = cache.trimPreviews().evictedPaths

        assertEquals(setOf(c.path, d.path), evicted)
        assertTrue(a.isFile && b.isFile)
        // Over the limit still, rather than blank tiles on screen.
        assertEquals(600L, previewBytes())
    }

    @Test fun aLowerLimitShrinksTheCache() = runBlocking {
        limit = 2_000L
        listOf("a", "b", "c", "d", "e").forEachIndexed { index, id -> preview(id, usedAt = 1_000L * (index + 1)) }
        assertFalse(cache.previewsNeedTrim())
        assertTrue(cache.trimPreviews(onlyIfNeeded = true).evictedPaths.isEmpty())
        assertEquals(1_500L, previewBytes())

        limit = 500L
        assertTrue(cache.previewsNeedTrim())
        cache.trimPreviews()

        assertTrue(previewBytes() <= 500L)
        assertFalse(cache.previewsNeedTrim())
    }

    @Test fun writesPastTheLimitAskForATrim() = runBlocking {
        preview("a", usedAt = 1_000)
        preview("b", usedAt = 2_000)
        preview("c", usedAt = 3_000)
        assertFalse(cache.previewsNeedTrim())
        preview("d", usedAt = 4_000)
        assertTrue(cache.previewsNeedTrim())
    }

    @Test fun wholeLibraryKeepsEveryPreview() = runBlocking {
        limit = null
        val previews = (1..20).map { preview("item-$it", usedAt = 1_000L * it) }

        assertFalse(cache.previewsNeedTrim())
        assertTrue(cache.trimPreviews().evictedPaths.isEmpty())
        assertTrue(previews.all(File::isFile))
        assertEquals(6_000L, previewBytes())
    }

    @Test fun wholeLibraryStillMakesRoomWhenThePhoneIsLowOnSpace() = runBlocking {
        limit = null
        val a = preview("a", usedAt = 1_000)
        val b = preview("b", usedAt = 2_000)
        val c = preview("c", usedAt = 3_000)
        cache.notePreviewsShown(listOf("a"))
        // 900 bytes short of the free space Alba keeps: previews go until it is back, except the one on screen.
        available = 2 * StorageWriteGuard.RESERVE_BYTES - 900

        assertTrue(cache.previewsNeedTrim())
        val evicted = cache.trimPreviews().evictedPaths

        assertEquals(setOf(b.path, c.path), evicted)
        assertTrue(a.isFile)
    }
}
