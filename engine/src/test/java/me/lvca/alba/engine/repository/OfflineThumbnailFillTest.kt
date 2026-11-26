package me.lvca.alba.engine.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.lvca.alba.engine.cache.InsufficientStorageException
import me.lvca.alba.engine.cache.MediaFileCache
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.model.OfflineThumbnailProgress
import me.lvca.alba.engine.model.OfflineThumbnails
import me.lvca.alba.engine.model.ThumbnailBudget
import me.lvca.alba.engine.source.CloudCatalogPage
import me.lvca.alba.engine.source.CloudCatalogSource
import me.lvca.alba.engine.source.CloudChanges
import me.lvca.alba.engine.source.KnownCloudRecords
import me.lvca.alba.engine.source.LibraryLookup
import me.lvca.alba.engine.source.RemoteMediaRecord
import me.lvca.alba.engine.source.ThrottledFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * The background fill of the offline set: the newest photos the budget holds, newest first, never one outside the
 * set, resumable from the catalog, and gentle with Apple and the phone's storage.
 */
@RunWith(AndroidJUnit4::class)
class OfflineThumbnailFillTest {
    private lateinit var context: Context
    private lateinit var database: AlbaDatabase
    private lateinit var cache: MediaFileCache
    private var available = Long.MAX_VALUE
    private var budget = ThumbnailBudget.GB_1
    private val source = LibrarySource(count = 10)
    private val pauses = mutableListOf<Long>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlbaDatabase::class.java).build()
        cache = MediaFileCache(context, availableBytes = { available }, cacheLimitBytes = { 1_000_000L })
            .also { runBlocking { it.clearAll() } }
    }

    @After
    fun tearDown() {
        runBlocking { cache.clearAll() }
        database.close()
    }

    /** A repository whose budgets hold 4, 6 and 8 photos; a new one on the same database stands for a new process. */
    private fun repository() = DefaultGalleryRepository(database, source, null, cache, thumbnailBudget = { budget },
        setSize = { mapOf(ThumbnailBudget.GB_1 to 4, ThumbnailBudget.GB_2 to 6, ThumbnailBudget.GB_4 to 8)[it] ?: Int.MAX_VALUE })

    private fun fill(repository: OfflineThumbnails, classify: (Throwable) -> FillFailure = ::fillFailure) =
        OfflineThumbnailFill(repository, classify = classify, pause = { pauses += it }, batchSize = 3, concurrency = 1)

    private suspend fun progress(repository: DefaultGalleryRepository): OfflineThumbnailProgress =
        repository.observeOfflineThumbnails(budget).first()

    @Test
    fun fillsTheNewestPhotosTheBudgetHoldsNewestFirst() = runBlocking {
        val repository = repository().also { it.refresh(false) }

        assertEquals(FillOutcome.Finished(skipped = 0), fill(repository).run())

        // Photo 9 was taken last; the budget holds four, so photos 0 to 5 are never downloaded.
        assertEquals(listOf(9, 8, 7, 6).map(source::id), source.downloads)
        assertEquals(OfflineThumbnailProgress(libraryCount = 10, setSize = 4, savedCount = 4), progress(repository))
        // Batches of three, with a pause between them and none before the first.
        assertEquals(listOf(OfflineThumbnailFill.BATCH_PAUSE_MILLIS), pauses)
        // Links are renewed a batch at a time, not once per photo.
        assertEquals(listOf(listOf(9, 8, 7), listOf(6)).map { it.map(source::id) }, source.prepared)
    }

    @Test
    fun theSetChangesOnlyWhenPhotosArriveOrTheBudgetDoes() = runBlocking {
        val repository = repository().also { it.refresh(false) }
        fill(repository).run()

        // Another refresh with nothing new: nothing to fill.
        repository.refresh(false)
        source.downloads.clear()
        fill(repository).run()
        assertTrue(source.downloads.isEmpty())

        // Two newer photos arrive: they are saved, and the two oldest of the set leave it without being replaced.
        source.changes = CloudChanges(listOf(source.record(10), source.record(11)), emptySet(), "token-later", false)
        repository.refresh(false)
        fill(repository).run()
        assertEquals(listOf(11, 10).map(source::id), source.downloads)
        assertEquals(OfflineThumbnailProgress(libraryCount = 12, setSize = 4, savedCount = 4), progress(repository))

        // A larger budget grows the set downward, by capture date. Photos 7 and 6 left the set into the cache and are
        // still on the phone, so only 5 and 4 are downloaded.
        budget = ThumbnailBudget.GB_4
        repository.applyStorageLimits()
        source.downloads.clear()
        fill(repository).run()
        assertEquals(listOf(5, 4).map(source::id), source.downloads)
        assertEquals(OfflineThumbnailProgress(libraryCount = 12, setSize = 8, savedCount = 8), progress(repository))
    }

    @Test
    fun scrollingToAnOlderPhotoFetchesItIntoTheCacheNotTheSet() = runBlocking {
        val repository = repository().also { it.refresh(false) }
        fill(repository).run()

        repository.ensurePreview(source.id(0))

        val usage = repository.localStorageUsage()
        assertEquals(4 * LibrarySource.THUMBNAIL_BYTES, usage.offlineThumbnailBytes)
        assertEquals(LibrarySource.THUMBNAIL_BYTES, usage.cacheBytes)
        // The fill still never downloads it, or anything else outside the set.
        source.downloads.clear()
        fill(repository).run()
        assertTrue(source.downloads.isEmpty())
    }

    @Test
    fun aRefreshReadsTheCatalogNoMoreForTheOfflineSet() = runBlocking {
        database.close()
        val queries = CopyOnWriteArrayList<String>()
        database = Room.inMemoryDatabaseBuilder(context, AlbaDatabase::class.java)
            .setQueryCallback({ sql, _ -> queries += sql.replace(Regex("\\s+"), " ").trim() }, Executor { it.run() })
            .build()
        val large = LibrarySource(count = 250)
        val repository = DefaultGalleryRepository(database, large, null, cache, thumbnailBudget = { budget }, setSize = { 4 })

        repository.refresh(false)

        // As DefaultGalleryRepositoryTest counts on a device: the read before the network and the one before publishing.
        assertEquals(2, queries.count { it.contains("WHERE c.origin = 'ICLOUD'") })
        assertTrue(queries.none { it.contains(OFFLINE_SET_QUERY) })

        // The set is named when a download first needs it: the oldest photo goes to the cache, not the set.
        queries.clear()
        repository.ensurePreview(large.id(0))
        assertEquals(1, queries.count { it.contains(OFFLINE_SET_QUERY) })
        assertEquals(LibrarySource.THUMBNAIL_BYTES, repository.localStorageUsage().cacheBytes)
    }

    @Test
    fun anInterruptedFillCarriesOnWhereItStoppedInANewProcess() = runBlocking {
        var allowed = 2
        val first = fill(repository().also { it.refresh(false) })
        source.onDownload = { allowed-- }

        assertEquals(FillOutcome.Interrupted, first.run(keepGoing = { allowed > 0 }))
        assertEquals(listOf(9, 8).map(source::id), source.downloads)

        // No cursor: the catalog says what is left, so nothing is downloaded twice.
        source.onDownload = {}
        assertEquals(FillOutcome.Finished(skipped = 0), fill(repository()).run())
        assertEquals(listOf(9, 8, 7, 6).map(source::id), source.downloads)
    }

    @Test
    fun throttlingEndsTheRunWithApplesWaitAndDownloadsNothingMore() = runBlocking {
        val repository = repository().also { it.refresh(false) }
        source.onDownload = { id -> if (id == source.id(7)) throw Throttled(retryAfterMillis = 120_000L) }

        assertEquals(FillOutcome.Throttled(retryAfterMillis = 120_000L), fill(repository).run())
        assertEquals(listOf(9, 8, 7).map(source::id), source.downloads)
        assertEquals(2, progress(repository).savedCount)
    }

    @Test
    fun aPhotoThatKeepsFailingIsSkippedForThisRunAndTriedInTheNext() = runBlocking {
        val repository = repository().also { it.refresh(false) }
        source.onDownload = { id -> if (id == source.id(8)) throw IOException("Apple returned HTTP 500") }

        assertEquals(FillOutcome.Finished(skipped = 1), fill(repository).run())
        assertEquals(OfflineThumbnailFill.MAX_ATTEMPTS, source.downloads.count { it == source.id(8) })
        assertEquals(3, progress(repository).savedCount)

        source.onDownload = {}
        source.downloads.clear()
        assertEquals(FillOutcome.Finished(skipped = 0), fill(repository).run())
        assertEquals(listOf(source.id(8)), source.downloads)
    }

    @Test
    fun theFillStopsBeforeTheLowStorageReserve() = runBlocking {
        val repository = repository().also { it.refresh(false) }
        available = MediaFileCache.LOW_STORAGE_FREE_BYTES + OfflineThumbnailFill.BATCH_ROOM_BYTES - 1

        assertEquals(FillOutcome.StorageLow, fill(repository).run())
        assertTrue(source.downloads.isEmpty())

        available = Long.MAX_VALUE
        assertEquals(FillOutcome.Finished(skipped = 0), fill(repository).run())
    }

    @Test
    fun aPhoneThatFillsUpMidBatchStopsTheRun() = runBlocking {
        val repository = repository().also { it.refresh(false) }
        source.onDownload = { id -> if (id == source.id(8)) throw InsufficientStorageException() }

        assertEquals(FillOutcome.StorageLow, fill(repository).run())
        assertEquals(listOf(9, 8).map(source::id), source.downloads)
    }

    @Test
    fun stoppingWaitsForTheDownloadInFlightAndDownloadsNothingAfter() = runBlocking {
        val repository = repository().also { it.refresh(false) }
        val inFlight = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        source.onSuspendingDownload = { id -> if (id == source.id(8)) { inFlight.complete(Unit); release.await() } }
        val fill = fill(repository)

        val run = async { fill.run() }
        inFlight.await()
        val stop = async { fill.stopAndJoin() }
        release.complete(Unit)
        stop.await()

        assertEquals(FillOutcome.Stopped, run.await())
        assertEquals(listOf(9, 8).map(source::id), source.downloads)
    }

    /**
     * The fill's two downloads used to hold both catalog permits, which every refresh page write needs, so on a large
     * library a refresh crept along behind the fill's whole queue. Downloads hold no permit now.
     */
    @Test
    fun aRefreshFinishesWhileTheFillsDownloadsAreStuck() = runBlocking {
        val repository = repository().also { it.refresh(false) }
        val bothInFlight = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var inFlight = 0
        source.onSuspendingDownload = {
            inFlight++
            if (inFlight == 2) bothInFlight.complete(Unit)
            release.await()
        }
        val fill = OfflineThumbnailFill(repository, pause = { pauses += it }, batchSize = 3, concurrency = 2)
        val run = async { fill.run() }
        bothInFlight.await()

        source.changes = CloudChanges(listOf(source.record(10)), emptySet(), "token-later", false)
        withTimeout(SHORT_WAIT) { repository.refresh(false) }
        assertTrue(repository.observeGallery().first().any { it.id == source.id(10) })

        release.complete(Unit)
        assertTrue(run.await() is FillOutcome.Finished)
    }

    /** While a refresh runs the fill starts no download; it carries on once the refresh is done. */
    @Test
    fun theFillWaitsForARunningRefresh() = runBlocking {
        val repository = repository().also { it.refresh(false) }
        val refreshing = CompletableDeferred<Unit>()
        val finishRefresh = CompletableDeferred<Unit>()
        source.onChanges = { refreshing.complete(Unit); finishRefresh.await() }
        val refresh = async { repository.refresh(false) }
        refreshing.await()

        val prepared = CompletableDeferred<Unit>()
        source.onPrepare = { prepared.complete(Unit) }
        val run = async { fill(repository).run() }
        // The batch is ready to download: only the refresh holds it back now.
        prepared.await()
        delay(NO_DOWNLOAD_WINDOW)
        assertTrue("No download while the refresh runs", source.downloads.isEmpty())

        finishRefresh.complete(Unit)
        refresh.await()
        assertEquals(FillOutcome.Finished(skipped = 0), run.await())
        assertEquals(listOf(9, 8, 7, 6).map(source::id), source.downloads)
    }

    /** Stopping a fill that waits for a refresh ends it at once, without waiting for the refresh. */
    @Test
    fun aStopEndsAFillWaitingForARefresh() = runBlocking {
        val repository = repository().also { it.refresh(false) }
        val refreshing = CompletableDeferred<Unit>()
        val finishRefresh = CompletableDeferred<Unit>()
        source.onChanges = { refreshing.complete(Unit); finishRefresh.await() }
        val refresh = async { repository.refresh(false) }
        refreshing.await()
        val prepared = CompletableDeferred<Unit>()
        source.onPrepare = { prepared.complete(Unit) }
        val fill = fill(repository)
        val run = async { fill.run() }
        prepared.await()

        withTimeout(SHORT_WAIT) { fill.stopAndJoin() }
        assertEquals(FillOutcome.Stopped, run.await())
        assertTrue(source.downloads.isEmpty())
        finishRefresh.complete(Unit)
        refresh.await()
        Unit
    }

    /**
     * An edit made while a refresh runs, after it read what changed, reaches the library through a refresh asked for
     * after the edit: that refresh waits for a follow-up instead of returning the running one's stale read.
     */
    @Test
    fun aRefreshAskedForDuringAnotherReadsWhatChangedSince() = runBlocking {
        val repository = repository().also { it.refresh(false) }
        val refreshing = CompletableDeferred<Unit>()
        val finishRefresh = CompletableDeferred<Unit>()
        source.onChanges = { if (refreshing.complete(Unit)) finishRefresh.await() }
        val running = async { repository.refresh(false) }
        refreshing.await()

        source.changes = CloudChanges(listOf(source.record(10)), emptySet(), "token-later", false)
        val afterEdit = async(start = CoroutineStart.UNDISPATCHED) { repository.refresh(false) }
        finishRefresh.complete(Unit)
        running.await()
        afterEdit.await()
        assertTrue("The edit is in the library", repository.observeGallery().first().any { it.id == source.id(10) })
    }

    /** However many refreshes are asked for while one runs (background work's, the screen's), they share one follow-up. */
    @Test
    fun refreshesAskedForDuringAnotherShareOneFollowUp() = runBlocking {
        val repository = repository().also { it.refresh(false) }
        val reads = source.changeReads
        val refreshing = CompletableDeferred<Unit>()
        val finishRefresh = CompletableDeferred<Unit>()
        source.onChanges = { if (refreshing.complete(Unit)) finishRefresh.await() }
        val background = async { repository.resumeCatalogScan() ?: repository.refresh(false) }
        refreshing.await()

        val asked = List(3) { async(start = CoroutineStart.UNDISPATCHED) { repository.refresh(it == 0) } }
        finishRefresh.complete(Unit)
        background.await()
        val followUps = asked.awaitAll().map { it.refreshedAtEpochMillis }.distinct()
        assertEquals("One follow-up for every request", 1, followUps.size)
        assertEquals("The running read and one follow-up", reads + 2, source.changeReads)
    }

    private companion object {
        const val SHORT_WAIT = 5_000L

        /** How long a check that nothing downloads gives the fill to download anyway. */
        const val NO_DOWNLOAD_WINDOW = 300L

        /** The newest-photos query that names the offline set. */
        const val OFFLINE_SET_QUERY = "SELECT c.mediaId FROM catalog_items c WHERE"
    }

    private class Throttled(override val retryAfterMillis: Long?) : IOException("HTTP 429"), ThrottledFailure

    private class LibrarySource(count: Int) : CloudCatalogSource {
        override var accountLabel = "account-a"
        private val items = (0 until count).toMutableList()
        var changes = CloudChanges(emptyList(), emptySet(), "token-after", false)
        val downloads = mutableListOf<String>()
        val prepared = mutableListOf<List<String>>()
        var onDownload: (String) -> Unit = {}
        var onSuspendingDownload: suspend (String) -> Unit = {}
        var onChanges: suspend () -> Unit = {}
        var onPrepare: suspend () -> Unit = {}
        var changeReads = 0

        fun id(index: Int) = "icloud:$accountLabel:asset-$index"

        /** Photo [index] was taken at [index] seconds: the higher, the newer. */
        fun record(index: Int) = RemoteMediaRecord(id(index), "IMG_$index.JPG", index * 1_000L, 10, 10, "rev-$index", 0, 0,
            masterRecordName = "master-$index", assetRecordName = "asset-$index")

        override suspend fun captureSyncToken() = "token-before"
        override suspend fun libraryItemCount() = items.size
        override suspend fun libraryRecords(assetRecordNames: List<String>) =
            LibraryLookup(items.filter { "asset-$it" in assetRecordNames }.map(::record), emptySet())
        override suspend fun fetchPage(cursor: String?, limit: Int) = CloudCatalogPage(items.map(::record), null, "rank:0")
        override suspend fun changes(token: String, known: KnownCloudRecords): CloudChanges {
            changeReads++
            // What changed is read when the call starts; a change made while it waits is the next read's.
            val read = changes
            onChanges()
            return read
        }
        override suspend fun prepareDownloads(mediaIds: List<String>) {
            prepared += mediaIds
            onPrepare()
        }
        override suspend fun writePreview(mediaId: String, output: OutputStream) {
            downloads += mediaId
            onDownload(mediaId)
            onSuspendingDownload(mediaId)
            output.write(ByteArray(THUMBNAIL_BYTES.toInt()))
        }
        override suspend fun writeOriginal(mediaId: String, output: OutputStream) = output.write(byteArrayOf(1))

        companion object {
            const val THUMBNAIL_BYTES = 100L
        }
    }
}
