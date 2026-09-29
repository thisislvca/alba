package dev.mela.engine.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.mela.engine.database.*
import dev.mela.engine.cache.MediaFileCache
import dev.mela.engine.model.*
import dev.mela.engine.source.*
import dev.mela.engine.companion.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

class LibraryFeaturesTest {
    @Test fun incrementalRefreshKeepsCachesAndFailedSnapshotKeepsVisibleState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MelaDatabase::class.java).build()
        val source = ChangingSource()
        val cache = MediaFileCache(context).also { it.clearAll() }
        val repository = DefaultGalleryRepository(db, source, null, cache)
        try {
            repository.refresh(false)
            repository.keepOriginalOffline("item")
            // The fake has no playback endpoint: these bytes must come from the private original.
            repository.openPlayback("item", 1, 1).use { read ->
                assertEquals(1L, read.length)
                assertEquals(2, read.input.read())
                assertEquals(-1, read.input.read())
            }
            assertTrue(runCatching { repository.openPlayback("item", -1, -1) }.isFailure)
            source.revision = "metadata-only"
            repository.refresh(false)
            assertEquals(1, source.snapshots)
            assertEquals(MediaAvailability.ORIGINAL_CACHED, repository.observeGallery().first().single().availability)
            assertTrue(repository.observeGallery(GalleryQuery(collectionId = GalleryQuery.FAVORITES)).first().single().isFavorite)
            source.failCollections = true
            source.deleted = true
            assertTrue(runCatching { repository.refresh(false) }.isFailure)
            assertEquals(1, repository.observeGallery().first().size)
            source.failCollections = false
            source.deleted = false
            source.expireOnce = true
            repository.refresh(false)
            assertEquals(2, source.snapshots)
            source.fingerprint = "new-bytes"
            repository.refresh(false)
            assertEquals(MediaAvailability.CLOUD_ONLY, repository.observeGallery().first().single().availability)
            source.deleted = true
            repository.refresh(false)
            assertTrue(repository.observeGallery().first().isEmpty())
        } finally { db.close(); cache.clearAll() }
    }

    @Test fun batchFailuresAreIsolatedAndDownloadsAreBounded() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MelaDatabase::class.java).build()
        var account = "a"
        var fail = true
        val active = AtomicInteger(); val maximum = AtomicInteger()
        val gallery = object : GalleryRepository {
            override fun observeGallery() = flowOf(emptyList<GalleryMedia>())
            override suspend fun refresh(includeDeviceMedia: Boolean) = RefreshSummary(0, 0, 0)
            override suspend fun ensurePreview(mediaId: String) = Unit
            override suspend fun keepOriginalOffline(mediaId: String) {
                val count = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, count) }
                try { delay(20); if (mediaId == "bad" && fail) error("failed") } finally { active.decrementAndGet() }
            }
            override suspend fun removeCachedOriginal(mediaId: String) = Unit
            override suspend fun clearCloudCatalog() = Unit
        }
        val photos = java.lang.reflect.Proxy.newProxyInstance(PhotoCompanion::class.java.classLoader, arrayOf(PhotoCompanion::class.java)) { _, _, _ -> error("No uploads expected") } as PhotoCompanion
        try {
            val actions = GalleryBatchActions(db.libraryDao(), gallery, photos) { account }
            val id = actions.enqueue(BatchAction.KEEP_OFFLINE, listOf("one", "two", "bad", "one"))
            actions.runPending()
            assertEquals(3, actions.observe().first().size)
            assertEquals(2, actions.observe().first().count { it.state == "DONE" })
            assertTrue(maximum.get() <= 2)
            fail = false; actions.retry(id); actions.runPending()
            assertTrue(actions.observe().first().all { it.state == "DONE" })
            actions.enqueue(BatchAction.KEEP_OFFLINE, listOf("old-account"))
            account = "b"; actions.runPending()
            assertEquals("STOPPED", actions.observe().first().last().state)
            db.libraryDao().putBatches(listOf(BatchItemEntity("restart", "uncertain-upload", "b", "UPLOAD", "RUNNING", null, Long.MAX_VALUE)))
            actions.runPending()
            assertEquals("NEEDS_ATTENTION", actions.observe().first().last().state)
            actions.enqueue(BatchAction.KEEP_OFFLINE, listOf("same-account-new-login"))
            actions.cancelActive()
            assertTrue(db.libraryDao().pendingBatches().isEmpty())
        } finally { db.close() }
    }

    private class ChangingSource : CloudCatalogSource {
        override val accountLabel = "a"
        var snapshots = 0; var revision = "one"; var fingerprint = "bytes"; var failCollections = false; var deleted = false; var expireOnce = false
        private fun item() = RemoteMediaRecord("item", "photo.jpg", 10, 10, 10, revision, 0, 0,
            masterRecordName = "master", assetRecordName = "asset", resourceFingerprint = fingerprint)
        override suspend fun captureSyncToken() = "start"
        override suspend fun fetchPage(cursor: String?, limit: Int): CloudCatalogPage { snapshots++; return CloudCatalogPage(listOf(item()), null, "unused-rank") }
        override suspend fun changes(token: String, known: List<RemoteMediaRecord>): CloudChanges {
            if (expireOnce) { expireOnce = false; throw CatalogResetRequired() }
            return CloudChanges(if (deleted) emptyList() else listOf(item()), if (deleted) setOf("asset") else emptySet(), "end", false)
        }
        override suspend fun collections(): CollectionSnapshot {
            if (failCollections) error("network interrupted")
            return CollectionSnapshot(listOf(GalleryCollection("album", "Trip")), mapOf("album" to setOf("item"), GalleryQuery.FAVORITES to setOf("item")))
        }
        override suspend fun writePreview(mediaId: String, output: OutputStream) { output.write(byteArrayOf(1)) }
        override suspend fun writeOriginal(mediaId: String, output: OutputStream) { output.write(byteArrayOf(1, 2)) }
    }
}
