package me.lvca.alba.engine.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.engine.cache.MediaFileCache
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.model.CatalogScanProgress
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.source.CloudCatalogPage
import me.lvca.alba.engine.source.CloudCatalogSource
import me.lvca.alba.engine.source.CloudChanges
import me.lvca.alba.engine.source.KnownCloudRecords
import me.lvca.alba.engine.source.RemoteMediaRecord
import me.lvca.alba.engine.source.ThrottledFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.io.OutputStream
import java.lang.ref.WeakReference

/** The first full library read: written page by page, resumable, fenced to one account, and patient with throttling. */
@RunWith(AndroidJUnit4::class)
class ResumableCatalogSyncTest {
    private lateinit var context: Context
    private lateinit var database: AlbaDatabase
    private lateinit var cache: MediaFileCache
    private val pauses = mutableListOf<Long>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlbaDatabase::class.java).build()
        cache = MediaFileCache(context, availableBytes = { Long.MAX_VALUE }).also { runBlocking { it.clearAll() } }
    }

    @After
    fun tearDown() {
        runBlocking { cache.clearAll() }
        database.close()
    }

    /** A new repository on the same database, as after the process was killed. */
    private fun repository(source: CloudCatalogSource) = DefaultGalleryRepository(database, source, null, cache, pause = { pauses += it })

    @Test
    fun aReadCutShortResumesFromTheSavedCursorAfterTheProcessDies() = runBlocking {
        val source = PagedSource(count = 50).apply { failAt = "30" }
        assertTrue(runCatching { repository(source).refresh(false) }.exceptionOrNull() is IOException)

        // Every page that landed is browsable and the read knows where it stopped; nothing is published as complete.
        assertEquals(30, repository(source).observeGallery().first().size)
        assertEquals(CatalogScanProgress(loadedCount = 30, totalCount = 50), repository(source).observeCatalogScan().first())
        assertNull(database.catalogDao().checkpoint("icloud:account-a"))

        source.requested.clear()
        val summary = repository(source).refresh(false)

        assertEquals(listOf<String?>("30", "40"), source.requested)
        assertEquals(50, summary.cloudItemCount)
        assertEquals("token-after", database.catalogDao().checkpoint("icloud:account-a")?.changeToken)
        assertNull(database.catalogSyncDao().scan("account-a"))
        assertNull(repository(source).observeCatalogScan().first())
        // The next refresh is incremental: no page is read again.
        source.requested.clear()
        repository(source).refresh(false)
        assertTrue(source.requested.isEmpty())
    }

    @Test
    fun anAccountChangeDuringTheReadDiscardsThePartialRead() = runBlocking {
        val source = PagedSource(count = 30)
        source.onFetch = { cursor -> if (cursor == "10") source.accountLabel = "account-b" }
        assertTrue(runCatching { repository(source).refresh(false) }.isFailure)
        // The page fetched after the switch was refused; only the first account's first page is in the catalog.
        assertEquals(10, database.catalogSyncDao().cloudCount())

        source.onFetch = {}
        source.requested.clear()
        repository(source).refresh(false)

        assertNull(source.requested.first())
        assertNull(database.catalogSyncDao().scan("account-a"))
        val ids = repository(source).observeGallery().first().map { it.id }
        assertEquals(30, ids.size)
        assertTrue(ids.all { it.startsWith("icloud:account-b:") })
    }

    @Test
    fun rowsTheReadDidNotSeeAreRemovedOnlyOnceItCompletes() = runBlocking {
        val source = PagedSource(count = 30)
        source.hidden = listOf(source.record(900).copy(isHidden = true))
        val first = repository(source)
        first.refresh(false)
        first.ensurePreview(source.id(5))
        // An update that needs every photo again, like schema 17, forgets the change token.
        database.catalogDao().deleteCheckpoints("icloud:%")
        source.count = 20
        source.hidden = emptyList()
        source.failAt = "10"

        assertTrue(runCatching { repository(source).refresh(false) }.isFailure)
        // The last complete catalog stays browsable while the read is unfinished, Hidden included.
        assertEquals(31, database.catalogSyncDao().cloudCount())
        assertNotNull(database.catalogDao().findById(source.id(25)))

        repository(source).refresh(false)

        val ids = repository(source).observeGallery().first().map { it.id }.toSet()
        assertEquals((0 until 20).map(source::id).toSet(), ids)
        assertEquals(MediaAvailability.PREVIEW_CACHED, repository(source).findMedia(source.id(5))?.availability)
    }

    @Test
    fun changesApplyToTheCatalogAndTrashKeepsItsCachedFiles() = runBlocking {
        val source = PagedSource(count = 10)
        val repository = repository(source)
        repository.refresh(false)
        repository.ensurePreview(source.id(4))
        source.changes = CloudChanges(listOf(source.record(99)), setOf("asset-3", "asset-4"), "token-later", false)
        source.trash = listOf(source.record(4).copy(isTrashed = true))

        repository.refresh(false)

        val items = repository.observeGallery().first().associateBy { it.id }
        assertFalse(source.id(3) in items)
        assertTrue(source.id(99) in items)
        assertTrue(items.getValue(source.id(4)).isTrashed)
        assertEquals(MediaAvailability.PREVIEW_CACHED, items.getValue(source.id(4)).availability)
        assertEquals("token-later", database.catalogDao().checkpoint("icloud:account-a")?.changeToken)
    }

    @Test
    fun aLibraryLargerThanTheOldLimitLoadsWithoutHoldingEarlierPages() = runBlocking {
        val source = PagedSource(count = 120_000, pageSize = 4_000)
        // A record from each page; the read must not keep any of them, or the page list, once its page is written.
        val pages = mutableListOf<List<WeakReference<Any>>>()
        var heldEarlierPage = false
        source.onPage = { records ->
            // Two pages back is out of the read's hands: the repository keeps no list of what it has read.
            pages.getOrNull(pages.size - 2)?.let { refs -> if (!refs.all(::collected)) heldEarlierPage = true }
            pages += listOf<WeakReference<Any>>(WeakReference(records), WeakReference(records.first()), WeakReference(records.last()))
        }

        val summary = repository(source).refresh(false)

        assertEquals(120_000, summary.cloudItemCount)
        assertFalse("A page read earlier was still reachable during the read", heldEarlierPage)
    }

    @Test
    fun throttlingWaitsAsLongAsICloudAsksThenBacksOffOnItsOwn() = runBlocking {
        val source = PagedSource(count = 20)
        source.throttles = ArrayDeque(listOf(5_000L, null, null))

        repository(source).refresh(false)

        // Waits as asked, and without an answer doubles from two seconds by attempt.
        assertEquals(listOf(5_000L, 4_000L, 8_000L), pauses)
        assertEquals(20, database.catalogSyncDao().cloudCount())
    }

    @Test
    fun aLongerWaitThanARefreshKeepsOpenEndsItAndKeepsTheReadForLater() = runBlocking {
        val source = PagedSource(count = 20)
        source.throttles = ArrayDeque(listOf(null, 600_000L))

        assertTrue(runCatching { repository(source).refresh(false) }.exceptionOrNull() is ThrottledFailure)
        assertEquals(listOf(2_000L), pauses)
        assertNotNull(database.catalogSyncDao().scan("account-a"))
    }

    /** Whether [ref]'s list could be collected, after asking the collector to run. */
    private fun collected(ref: WeakReference<*>): Boolean {
        repeat(GC_ATTEMPTS) {
            if (ref.get() == null) return true
            System.gc()
        }
        return ref.get() == null
    }

    private class Throttled(override val retryAfterMillis: Long?) : IOException("Throttled"), ThrottledFailure

    /** A library of [count] items in pages of [pageSize]; cursors are offsets. */
    private class PagedSource(var count: Int, private val pageSize: Int = 10) : CloudCatalogSource {
        override var accountLabel = "account-a"
        val requested = mutableListOf<String?>()
        var failAt: String? = null
        var onFetch: (String?) -> Unit = {}
        var onPage: (List<RemoteMediaRecord>) -> Unit = {}
        var throttles = ArrayDeque<Long?>()
        var hidden = emptyList<RemoteMediaRecord>()
        var trash = emptyList<RemoteMediaRecord>()
        var changes = CloudChanges(emptyList(), emptySet(), "token-after", false)

        fun id(index: Int) = "icloud:$accountLabel:asset-$index"
        fun record(index: Int) = RemoteMediaRecord(id(index), "IMG_$index.JPG", index.toLong(), 10, 10, "rev-$index", 0, 0,
            masterRecordName = "master-$index", assetRecordName = "asset-$index")

        override suspend fun captureSyncToken() = "token-before"
        override suspend fun libraryItemCount() = count
        override suspend fun fetchPage(cursor: String?, limit: Int): CloudCatalogPage {
            requested += cursor
            if (throttles.isNotEmpty()) throw Throttled(throttles.removeFirst())
            if (cursor != null && cursor == failAt) { failAt = null; throw IOException("offline") }
            onFetch(cursor)
            val offset = cursor?.toInt() ?: 0
            val records = (offset until minOf(count, offset + pageSize)).map(::record)
            onPage(records)
            return CloudCatalogPage(records, (offset + pageSize).takeIf { it < count }?.toString(), "rank:$offset")
        }
        override suspend fun changes(token: String, known: KnownCloudRecords) = changes
        override suspend fun hiddenMedia() = hidden
        override suspend fun recentlyDeleted() = trash
        override suspend fun writePreview(mediaId: String, output: OutputStream) = output.write(byteArrayOf(1))
        override suspend fun writeOriginal(mediaId: String, output: OutputStream) = output.write(byteArrayOf(1, 2))
    }

    private companion object {
        const val GC_ATTEMPTS = 10
    }
}
