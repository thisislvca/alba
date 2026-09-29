package dev.mela.engine.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mela.engine.cache.MediaFileCache
import dev.mela.engine.database.MelaDatabase
import dev.mela.engine.model.MediaAvailability
import dev.mela.engine.source.CloudCatalogPage
import dev.mela.engine.source.CloudCatalogSource
import dev.mela.engine.source.RemoteMediaRecord
import java.io.OutputStream
import java.util.concurrent.Executor
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultGalleryRepositoryTest {
    private lateinit var database: MelaDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MelaDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun partialScanShowsNewPhotosWithoutDeletingOfflineItemsOrSavingAToken() = runBlocking {
        val secondPage = CompletableDeferred<Unit>()
        val failPage = CompletableDeferred<Unit>()
        var partial = false
        val source = object : CloudCatalogSource by OneItemCloudSource {
            override suspend fun fetchPage(cursor: String?, limit: Int): CloudCatalogPage {
                if (!partial) return OneItemCloudSource.fetchPage(cursor, limit)
                if (cursor != null) { secondPage.complete(Unit); failPage.await(); throw java.io.IOException("offline") }
                return OneItemCloudSource.fetchPage(null, limit).let { page ->
                    page.copy(records = page.records.map { it.copy(id = "new-photo") }, nextCursor = "next")
                }
            }
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = DefaultGalleryRepository(database, source, null, MediaFileCache(context).also { it.clearAll() })
        repository.refresh(false)
        repository.keepOriginalOffline(ITEM_ID)
        partial = true
        val refresh = async { runCatching { repository.refresh(false) } }
        try {
            withTimeout(5_000) { secondPage.await() }
            assertEquals(2, repository.observeGallery().first().size)
            assertEquals(MediaAvailability.ORIGINAL_CACHED, repository.findMedia(ITEM_ID)?.availability)
        } finally { failPage.complete(Unit) }
        assertTrue(refresh.await().exceptionOrNull() is java.io.IOException)
        assertEquals(null, database.catalogDao().checkpoint("icloud:" + source.accountLabel))
        assertEquals(MediaAvailability.ORIGINAL_CACHED, repository.findMedia(ITEM_ID)?.availability)
    }

    @Test
    fun thumbnailCanFinishDuringRefreshAndItsFileSurvivesPublication() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var blockFetch = false
        val source = object : CloudCatalogSource by OneItemCloudSource {
            override suspend fun fetchPage(cursor: String?, limit: Int): CloudCatalogPage {
                if (blockFetch) { entered.complete(Unit); resume.await() }
                return OneItemCloudSource.fetchPage(cursor, limit)
            }
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cache = MediaFileCache(context).also { it.clearAll() }
        val repository = DefaultGalleryRepository(database, source, null, cache)
        repository.refresh(false)
        blockFetch = true
        val refresh = async { repository.refresh(false) }
        try {
            withTimeout(5_000) { entered.await() }
            withTimeout(5_000) { repository.ensurePreview(ITEM_ID) }
        } finally { resume.complete(Unit) }
        refresh.await()
        val photo = repository.observeGallery().first().single()
        assertEquals(MediaAvailability.PREVIEW_CACHED, photo.availability)
        assertTrue(java.io.File(requireNotNull(photo.previewReference)).exists())
    }

    @Test
    fun cacheWriteDoesNotInvalidateCatalogMetadata() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = DefaultGalleryRepository(database, OneItemCloudSource, null, MediaFileCache(context).also { it.clearAll() })
        repository.refresh(false)
        database.catalogDao().observeAll().first()
        val invalidations = AtomicInteger()
        val observer = object : androidx.room.InvalidationTracker.Observer("catalog_items") {
            override fun onInvalidated(tables: Set<String>) { invalidations.incrementAndGet() }
        }
        database.invalidationTracker.addObserver(observer)
        try {
            repository.ensurePreview(ITEM_ID)
            database.catalogDao().observeCache().first { it.single().previewCachePath != null }
            assertEquals(0, invalidations.get())
            assertTrue(database.catalogDao().findById(ITEM_ID)?.previewCachePath != null)
        } finally { database.invalidationTracker.removeObserver(observer) }
    }

    @Test
    fun cachedBytesDriveTruthfulAvailabilityTransitions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cache = MediaFileCache(context).also { it.clearAll() }
        val repository = DefaultGalleryRepository(
            database = database,
            cloudSource = OneItemCloudSource,
            deviceSource = null,
            cache = cache,
            now = { 1234L },
        )

        repository.refresh(includeDeviceMedia = false)
        assertEquals(MediaAvailability.CLOUD_ONLY, repository.observeGallery().first().single().availability)

        repository.ensurePreview(ITEM_ID)
        assertEquals(MediaAvailability.PREVIEW_CACHED, repository.observeGallery().first().single().availability)

        repository.keepOriginalOffline(ITEM_ID)
        assertEquals(MediaAvailability.ORIGINAL_CACHED, repository.observeGallery().first().single().availability)

        repository.removeCachedOriginal(ITEM_ID)
        assertEquals(MediaAvailability.PREVIEW_CACHED, repository.observeGallery().first().single().availability)

        repository.keepOriginalOffline(ITEM_ID)
        repository.refresh(includeDeviceMedia = false)
        val cached = repository.observeGallery().first().single()
        assertEquals(MediaAvailability.ORIGINAL_CACHED, cached.availability)
        repository.clearCloudCatalog()

        assertTrue(repository.observeGallery().first().isEmpty())
        assertFalse(java.io.File(requireNotNull(cached.previewReference)).exists())
        assertFalse(java.io.File(requireNotNull(cached.originalReference)).exists())
    }

    @Test
    fun refreshPrunesPrivateFilesThatNoCatalogRowOwns() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cache = MediaFileCache(context).also { it.clearAll() }
        val orphan = java.io.File(context.cacheDir, "gallery_media/orphan.tmp").apply {
            writeBytes(byteArrayOf(9, 8, 7))
            setLastModified(1L)
        }
        val repository = DefaultGalleryRepository(
            database = database,
            cloudSource = OneItemCloudSource,
            deviceSource = null,
            cache = cache,
        )

        repository.refresh(includeDeviceMedia = false)

        assertFalse(orphan.exists())
        assertEquals(1, repository.observeGallery().first().size)
    }

    @Test
    fun refreshReadsCacheBeforeNetworkAndAgainBeforePublicationWithoutPerItemQueries() = runBlocking {
        database.close()
        val queries = CopyOnWriteArrayList<String>()
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MelaDatabase::class.java,
        ).setQueryCallback(
            { sql, _ -> queries += sql.replace(Regex("\\s+"), " ").trim() },
            Executor { command -> command.run() },
        ).build()
        val repository = DefaultGalleryRepository(
            database = database,
            cloudSource = PagedCloudSource(itemCount = 250),
            deviceSource = null,
            cache = MediaFileCache(ApplicationProvider.getApplicationContext()),
        )

        repository.refresh(includeDeviceMedia = false)

        assertEquals(
            2,
            queries.count { query ->
                query.contains("WHERE c.origin = 'ICLOUD'")
            },
        )
        assertFalse(queries.any { query -> query.contains("WHERE c.mediaId = ?") })
    }

    @Test
    fun concurrentRefreshCallsAreSerialized() = runBlocking {
        val source = BlockingCloudSource()
        val repository = DefaultGalleryRepository(
            database = database,
            cloudSource = source,
            deviceSource = null,
            cache = MediaFileCache(ApplicationProvider.getApplicationContext()),
        )

        val first = async { repository.refresh(includeDeviceMedia = false) }
        source.firstFetchStarted.await()
        val second = async { repository.refresh(includeDeviceMedia = false) }
        yield()
        source.releaseFirstFetch.complete(Unit)
        first.await()
        second.await()

        assertEquals(2, source.fetchCount.get())
        assertEquals(1, source.maxConcurrentFetches.get())
    }

    @Test
    fun viewerCacheEvictsOldestBytesWithoutRemovingThumbnailsOrOfflineOriginals() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cache = MediaFileCache(context, maxViewerBytes = 5).also { it.clearAll() }
        val oldPreview = cache.writeAtomically("old", "viewer") { it.write(byteArrayOf(1, 2, 3, 4)) }
        val newPreview = cache.writeAtomically("new", "viewer") { it.write(byteArrayOf(5, 6, 7, 8)) }
        val thumbnail = cache.writeAtomically("browsed", "preview") { it.write(ByteArray(20)) }
        val original = cache.writeAtomically("kept", "original") { it.write(byteArrayOf(9, 10, 11, 12)) }
        oldPreview.setLastModified(1L)
        newPreview.setLastModified(2L)

        val result = cache.pruneTo(
            setOf(oldPreview.path, newPreview.path, thumbnail.path, original.path),
        )

        assertFalse(oldPreview.exists())
        assertTrue(newPreview.exists())
        assertTrue(original.exists())
        assertTrue(thumbnail.exists())
        assertEquals(setOf(oldPreview.path), result.evictedPaths)
        cache.clearAll()
        Unit
    }

    @Test
    fun offlineOriginalSurvivesEvictableCacheDeletion() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cache = MediaFileCache(context).also { it.clearAll() }
        val repository = DefaultGalleryRepository(database, OneItemCloudSource, null, cache)
        repository.refresh(false)
        repository.keepOriginalOffline(ITEM_ID)
        val path = requireNotNull(repository.observeGallery().first().single().originalReference)
        assertTrue(path.startsWith(context.filesDir.canonicalPath + "/"))
        java.io.File(context.cacheDir, "gallery_media").listFiles().orEmpty().forEach { it.delete() }
        assertEquals(MediaAvailability.ORIGINAL_CACHED, repository.observeGallery().first().single().availability)
        assertTrue(java.io.File(path).isFile)
        cache.clearAll()
    }

    @Test
    fun viewerBudgetDoesNotEvictBrowsedThumbnails() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cache = MediaFileCache(context, maxViewerBytes = 2).also { it.clearAll() }
        val repository = DefaultGalleryRepository(database, OneItemCloudSource, null, cache)
        repository.refresh(false)
        repository.ensurePreview(ITEM_ID)
        repository.ensureViewer(ITEM_ID)
        assertEquals(null, database.catalogDao().findById(ITEM_ID)?.viewerCachePath)
        val preview = requireNotNull(database.catalogDao().findById(ITEM_ID)?.previewCachePath)
        assertTrue(preview.startsWith(context.filesDir.canonicalPath + "/gallery_previews/"))
        assertEquals(MediaAvailability.PREVIEW_CACHED, repository.observeGallery().first().single().availability)
        java.io.File(context.cacheDir, "gallery_media").listFiles().orEmpty().forEach { it.delete() }
        val offlineCloud = object : CloudCatalogSource by OneItemCloudSource {
            override suspend fun writePreview(mediaId: String, output: OutputStream): Unit = error("No network")
        }
        val restarted = DefaultGalleryRepository(database, offlineCloud, null, MediaFileCache(context))
        restarted.ensurePreview(ITEM_ID)
        assertEquals(preview, restarted.observeGallery().first().single().previewReference)
        cache.clearAll()
    }

    @Test fun legacyThumbnailsMoveToDurableStorageAndSourceChangesInvalidateThem() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cache = MediaFileCache(context).also { it.clearAll() }
        val repository = DefaultGalleryRepository(database, OneItemCloudSource, null, cache)
        try {
            repository.refresh(false)
            repository.ensurePreview(ITEM_ID)
            val preview = java.io.File(requireNotNull(database.catalogDao().findById(ITEM_ID)?.previewCachePath))
            val legacy = java.io.File(context.cacheDir, "gallery_media/${preview.name}")
            preview.copyTo(legacy, overwrite = true)
            preview.delete()
            database.catalogDao().setPreviewPath(ITEM_ID, legacy.path)
            val offline = object : CloudCatalogSource by OneItemCloudSource {
                override suspend fun fetchPage(cursor: String?, limit: Int): CloudCatalogPage = error("Offline")
            }
            assertTrue(runCatching { DefaultGalleryRepository(database, offline, null, cache).refresh(false) }.isFailure)
            assertTrue(preview.isFile)
            assertEquals(preview.path, repository.observeGallery().first().single().previewReference)
            repository.refresh(false)
            assertTrue(preview.isFile)
            assertFalse(legacy.exists())
            assertEquals(preview.path, repository.observeGallery().first().single().previewReference)

            val changed = object : CloudCatalogSource by OneItemCloudSource {
                override suspend fun fetchPage(cursor: String?, limit: Int): CloudCatalogPage {
                    val page = OneItemCloudSource.fetchPage(cursor, limit)
                    return page.copy(records = page.records.map { it.copy(resourceFingerprint = "new-photo-bytes") })
                }
            }
            val updated = DefaultGalleryRepository(database, changed, null, cache)
            updated.refresh(false)
            assertFalse(preview.exists())
            assertEquals(null, updated.observeGallery().first().single().previewReference)
        } finally { cache.clearAll() }
    }

    @Test fun differentThumbnailsDownloadConcurrentlyWithoutFetchingOriginals() = runBlocking<Unit> {
        val cache = MediaFileCache(ApplicationProvider.getApplicationContext()).also { it.clearAll() }
        val bothStarted = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        val cloud = object : CloudCatalogSource by PagedCloudSource(2) {
            override suspend fun writePreview(mediaId: String, output: OutputStream) {
                if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
                finish.await()
                output.write(byteArrayOf(1, 2, 3))
            }
            override suspend fun writeOriginal(mediaId: String, output: OutputStream): Unit = error("Scrolling requested an original")
        }
        val repository = DefaultGalleryRepository(database, cloud, null, cache)
        try {
            repository.refresh(false)
            val first = async { repository.ensurePreview("icloud:test:0") }
            val second = async { repository.ensurePreview("icloud:test:1") }
            try { withTimeout(5_000) { bothStarted.await() } }
            finally { finish.complete(Unit) }
            first.await(); second.await()
            assertTrue(repository.observeGallery().first().all { it.availability == MediaAvailability.PREVIEW_CACHED })
            repository.clearCloudCatalog()
            assertTrue(java.io.File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "gallery_previews").listFiles().orEmpty().isEmpty())
        } finally { cache.clearAll() }
    }

    @Test
    fun failedCloudRefreshStillPublishesDeviceScanAndRetainsOfflineOriginal() = runBlocking<Unit> {
        val cache = MediaFileCache(ApplicationProvider.getApplicationContext()).also { it.clearAll() }
        val initial = DefaultGalleryRepository(database, OneItemCloudSource, null, cache)
        initial.refresh(false)
        initial.keepOriginalOffline(ITEM_ID)
        val brokenCloud = object : CloudCatalogSource by OneItemCloudSource {
            override suspend fun fetchPage(cursor: String?, limit: Int): CloudCatalogPage = throw java.io.IOException("Offline")
        }
        val device = object : dev.mela.engine.source.DeviceCatalogSource {
            override suspend fun scanImages() = listOf(dev.mela.engine.source.DeviceMediaRecord(
                "device:1", "phone.jpg", 42, 100, 100, "content://media/external/images/media/1", "1"))
            override suspend fun writeOriginal(contentUri: String, output: OutputStream): dev.mela.engine.source.DeviceOriginalEvidence = error("Unused")
        }
        val repository = DefaultGalleryRepository(database, brokenCloud, device, cache)
        assertTrue(runCatching { repository.refresh(true) }.isFailure)
        val gallery = repository.observeGallery().first()
        assertTrue(gallery.any { it.id == "device:1" })
        assertEquals(MediaAvailability.ORIGINAL_CACHED, gallery.single { it.id == ITEM_ID }.availability)
        cache.clearAll()
    }

    private object OneItemCloudSource : CloudCatalogSource {
        override val accountLabel: String = "test"

        override suspend fun fetchPage(cursor: String?, limit: Int): CloudCatalogPage = CloudCatalogPage(
            records = listOf(
                RemoteMediaRecord(
                    id = ITEM_ID,
                    fileName = "fixture.jpg",
                    capturedAtEpochMillis = 1000L,
                    width = 1200,
                    height = 900,
                    sourceRevision = "1",
                    accentStartArgb = 0xFF000000,
                    accentEndArgb = 0xFFFFFFFF,
                ),
            ),
            nextCursor = null,
            changeToken = "1",
        )

        override suspend fun writePreview(mediaId: String, output: OutputStream) {
            output.write(byteArrayOf(1, 2, 3))
        }

        override suspend fun writeOriginal(mediaId: String, output: OutputStream) {
            output.write(byteArrayOf(4, 5, 6))
        }
    }

    private class PagedCloudSource(itemCount: Int) : CloudCatalogSource {
        private val records = List(itemCount) { index ->
            RemoteMediaRecord(
                id = "icloud:test:$index",
                fileName = "$index.jpg",
                capturedAtEpochMillis = index.toLong(),
                width = 100,
                height = 100,
                sourceRevision = "1",
                accentStartArgb = 0,
                accentEndArgb = 0,
            )
        }

        override val accountLabel = "paged-test"

        override suspend fun fetchPage(cursor: String?, limit: Int): CloudCatalogPage {
            val start = cursor?.toInt() ?: 0
            val end = minOf(start + limit, records.size)
            return CloudCatalogPage(
                records = records.subList(start, end),
                nextCursor = end.takeIf { it < records.size }?.toString(),
                changeToken = end.toString(),
            )
        }

        override suspend fun writePreview(mediaId: String, output: OutputStream) = Unit

        override suspend fun writeOriginal(mediaId: String, output: OutputStream) = Unit
    }

    private class BlockingCloudSource : CloudCatalogSource {
        val firstFetchStarted = CompletableDeferred<Unit>()
        val releaseFirstFetch = CompletableDeferred<Unit>()
        val fetchCount = AtomicInteger()
        val maxConcurrentFetches = AtomicInteger()
        private val activeFetches = AtomicInteger()

        override val accountLabel = "blocking-test"

        override suspend fun fetchPage(cursor: String?, limit: Int): CloudCatalogPage {
            val call = fetchCount.incrementAndGet()
            val active = activeFetches.incrementAndGet()
            maxConcurrentFetches.updateAndGet { previous -> maxOf(previous, active) }
            return try {
                if (call == 1) {
                    firstFetchStarted.complete(Unit)
                    releaseFirstFetch.await()
                }
                CloudCatalogPage(
                    records = emptyList(),
                    nextCursor = null,
                    changeToken = call.toString(),
                )
            } finally {
                activeFetches.decrementAndGet()
            }
        }

        override suspend fun writePreview(mediaId: String, output: OutputStream) = Unit

        override suspend fun writeOriginal(mediaId: String, output: OutputStream) = Unit
    }

    private companion object {
        const val ITEM_ID = "icloud:test:1"
    }
}
