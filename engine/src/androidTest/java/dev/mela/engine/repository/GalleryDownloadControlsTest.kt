package dev.mela.engine.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.mela.engine.cache.*
import dev.mela.engine.companion.*
import dev.mela.engine.database.*
import dev.mela.engine.model.*
import dev.mela.engine.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

class GalleryDownloadControlsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun database() = Room.inMemoryDatabaseBuilder(context, MelaDatabase::class.java).build()
    private fun photos() = java.lang.reflect.Proxy.newProxyInstance(PhotoCompanion::class.java.classLoader,
        arrayOf(PhotoCompanion::class.java)) { _, _, _ -> error("No upload should be invoked") } as PhotoCompanion
    private fun gallery(download: suspend (String) -> Unit = {}) = object : GalleryRepository {
        override fun observeGallery() = flowOf(emptyList<GalleryMedia>())
        override suspend fun refresh(includeDeviceMedia: Boolean) = RefreshSummary(0, 0, 0)
        override suspend fun ensurePreview(mediaId: String) = Unit
        override suspend fun keepOriginalOffline(mediaId: String) = download(mediaId)
        override suspend fun removeCachedOriginal(mediaId: String) = Unit
        override suspend fun clearCloudCatalog() = Unit
    }

    @Test fun boundedPassLeavesRemainingRowsForTheNextPass() = runBlocking {
        val db = database()
        val calls = ConcurrentHashMap.newKeySet<String>()
        val actions = GalleryBatchActions(db.libraryDao(), gallery { calls += it }, photos()) { "account" }
        try {
            actions.enqueue(BatchAction.KEEP_OFFLINE, (1..10).map { "photo-$it" })
            assertTrue(actions.runPending(limit = 3))
            assertEquals(3, calls.size)
            assertEquals(7, db.libraryDao().pendingBatches().size)
            while (actions.runPending(limit = 3)) Unit
            assertEquals(10, calls.size)
            assertFalse(actions.hasPending())
        } finally { db.close() }
    }

    @Test fun localCleanupDoesNotStartQueuedNetworkTransfers() = runBlocking {
        val db = database()
        val actions = GalleryBatchActions(db.libraryDao(), gallery { error("Network work must stay queued") }, photos()) { "account" }
        try {
            val download = actions.enqueue(BatchAction.KEEP_OFFLINE, listOf("download"))
            val cleanup = actions.enqueue(BatchAction.REMOVE_CACHE, listOf("cached"))
            assertFalse(actions.runPending(localOnly = true))
            val rows = actions.observe().first()
            assertEquals("DONE", rows.single { it.batchId == cleanup }.state)
            assertEquals("WAITING", rows.single { it.batchId == download }.state)
            assertTrue(actions.hasPending())
        } finally { db.close() }
    }

    @Test fun cancelKeepsCompletedItemsAndDoesNotCancelAnotherBatchOrReplayOnRestart() = runBlocking {
        val db = database()
        val calls = ConcurrentHashMap.newKeySet<String>()
        val gallery = gallery { id -> calls += id; if (id in setOf("01-running", "02-running", "03-waiting")) awaitCancellation() }
        val actions = GalleryBatchActions(db.libraryDao(), gallery, photos()) { "account" }
        var run: Job? = null
        try {
            val id = actions.enqueue(BatchAction.KEEP_OFFLINE, listOf("00-completed", "01-running", "02-running", "03-waiting"))
            run = launch { actions.runPending() }
            withTimeout(5_000) { actions.observe().first { rows -> rows.any { it.mediaId == "00-completed" && it.state == "DONE" } && rows.count { it.state == "RUNNING" } == 2 } }
            val other = actions.enqueue(BatchAction.KEEP_OFFLINE, listOf("unrelated"))
            actions.cancelDownload(id)
            run.join()
            val canceled = actions.observe().first().filter { it.batchId == id }
            assertEquals(1, canceled.count { it.state == "DONE" })
            assertEquals(3, canceled.count { it.state == "CANCELED" })
            assertFalse(calls.contains("03-waiting"))
            val restarted = GalleryBatchActions(db.libraryDao(), gallery, photos()) { "account" }
            restarted.runPending()
            assertEquals("DONE", restarted.observe().first().single { it.batchId == other }.state)
            assertFalse(calls.contains("03-waiting"))
            val queued = restarted.enqueue(BatchAction.KEEP_OFFLINE, listOf("never-start"))
            restarted.cancelDownload(queued); restarted.runPending()
            assertFalse(calls.contains("never-start"))
        } finally { run?.cancelAndJoin(); db.close() }
    }

    @Test fun cancelAllStopsRunningAndQueuedItemsButKeepsCompletedItemsAfterRestart() = runBlocking {
        val db = database()
        val calls = ConcurrentHashMap.newKeySet<String>()
        val gallery = gallery { id -> calls += id; if (id != "00-completed") awaitCancellation() }
        val actions = GalleryBatchActions(db.libraryDao(), gallery, photos()) { "account" }
        var run: Job? = null
        try {
            actions.enqueue(BatchAction.KEEP_OFFLINE, listOf("00-completed", "01-running", "02-running", "03-waiting"))
            run = launch { actions.runPending() }
            withTimeout(5_000) { actions.observe().first { rows -> rows.any { it.state == "DONE" } && rows.count { it.state == "RUNNING" } == 2 } }
            actions.enqueue(BatchAction.KEEP_OFFLINE, listOf("04-queued"))
            actions.cancelActive()
            run.join()
            val restarted = GalleryBatchActions(db.libraryDao(), gallery, photos()) { "account" }
            assertFalse(restarted.runPending())
            val rows = restarted.observe().first()
            assertEquals(1, rows.count { it.state == "DONE" })
            assertEquals(4, rows.count { it.state == "STOPPED" })
            assertFalse(calls.contains("03-waiting"))
            assertFalse(calls.contains("04-queued"))
        } finally { run?.cancelAndJoin(); db.close() }
    }

    @Test fun phoneExportRetriesKnownFailuresButNeverReplaysAnInterruptedPublication() = runBlocking {
        val db = database()
        val calls = mutableListOf<String>()
        var fail = true
        val actions = GalleryBatchActions(db.libraryDao(), gallery(), photos(), saveToPhone = { id ->
            synchronized(calls) { calls += id }; if (id == "bad" && fail) error("failed before publication")
        }) { "account" }
        try {
            val id = actions.enqueue(BatchAction.SAVE_TO_PHONE, listOf("good", "bad"))
            actions.runPending()
            assertEquals(1, actions.observe().first().count { it.state == "DONE" })
            fail = false; actions.retry(id); actions.runPending()
            assertEquals(1, calls.count { it == "good" })
            assertEquals(2, calls.count { it == "bad" })
            db.libraryDao().putBatches(listOf(BatchItemEntity("interrupted", "uncertain", "account", "SAVE_TO_PHONE", "RUNNING", null, Long.MAX_VALUE)))
            actions.runPending()
            assertEquals("NEEDS_ATTENTION", actions.observe().first().single { it.batchId == "interrupted" }.state)
            assertFalse(calls.contains("uncertain"))
        } finally { db.close() }
    }

    @Test fun lowSpaceAndCancellationNeverReplaceCompletedBytesOrLeavePartialFiles() = runBlocking {
        var available = Long.MAX_VALUE
        val cache = MediaFileCache(context, availableBytes = { available })
        cache.clearAll()
        try {
            val original = cache.writeAtomically("same", "original") { it.write(byteArrayOf(1, 2, 3)) }
            val failure = runCatching { cache.writeAtomically("same", "original") {
                it.write(ByteArray(256 * 1024)); available = 0; it.write(ByteArray(256 * 1024))
            } }.exceptionOrNull()
            assertTrue(failure is InsufficientStorageException)
            assertArrayEquals(byteArrayOf(1, 2, 3), original.readBytes())
            assertTrue(original.parentFile!!.listFiles()!!.none { it.extension == "tmp" })
            available = Long.MAX_VALUE
            val started = CompletableDeferred<Unit>()
            val job = launch { cache.writeAtomically("canceled", "preview") { it.write(byteArrayOf(4)); started.complete(Unit); awaitCancellation() } }
            started.await(); job.cancelAndJoin()
            assertEquals(0L, cache.usage().previewBytes)
            assertArrayEquals(byteArrayOf(1, 2, 3), original.readBytes())
            assertTrue(java.io.File(context.filesDir, "gallery_previews").listFiles()!!.isEmpty())
        } finally { cache.clearAll() }
    }

    @Test fun cleanupSeparatesThumbnailsViewingCacheAndOfflineOriginals() = runBlocking {
        val db = database()
        val cache = MediaFileCache(context).also { it.clearAll() }
        val source = object : CloudCatalogSource {
            override val accountLabel = "account"
            override suspend fun fetchPage(cursor: String?, limit: Int) = CloudCatalogPage(listOf(RemoteMediaRecord("photo", "photo.jpg", 1, 10, 10, "one", 0, 0)), null, "")
            override suspend fun writePreview(mediaId: String, output: OutputStream) { output.write(byteArrayOf(1)) }
            override suspend fun writeOriginal(mediaId: String, output: OutputStream) { output.write(byteArrayOf(2, 3)) }
        }
        val repository = DefaultGalleryRepository(db, source, null, cache)
        try {
            repository.refresh(false); repository.ensurePreview("photo"); repository.ensureViewer("photo"); repository.keepOriginalOffline("photo")
            assertEquals(1L, repository.localStorageUsage().previewBytes)
            assertTrue(repository.localStorageUsage().viewerBytes > 0)
            repository.clearLocalMedia(LocalMediaCategory.VIEWERS)
            assertEquals(0L, repository.localStorageUsage().viewerBytes)
            var item = repository.observeGallery().first().single()
            assertNotNull(item.previewReference); assertNotNull(item.originalReference); assertNull(item.viewerReference)
            repository.clearLocalMedia(LocalMediaCategory.PREVIEWS)
            item = repository.observeGallery().first().single()
            assertNull(item.previewReference); assertNotNull(item.originalReference)
            repository.clearLocalMedia(LocalMediaCategory.ORIGINALS)
            assertEquals(MediaAvailability.CLOUD_ONLY, repository.observeGallery().first().single().availability)
            repository.ensurePreview("photo")
            assertNotNull(repository.observeGallery().first().single().previewReference)
        } finally { db.close(); cache.clearAll() }
    }
}
