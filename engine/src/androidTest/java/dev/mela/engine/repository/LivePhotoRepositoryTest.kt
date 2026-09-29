package dev.mela.engine.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.mela.engine.cache.MediaFileCache
import dev.mela.engine.database.MelaDatabase
import dev.mela.engine.model.*
import dev.mela.engine.source.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LivePhotoRepositoryTest {
    @Test fun offlineLivePhotoRetainsBothResourcesAndPlaybackUsesMotion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MelaDatabase::class.java).build()
        val cache = MediaFileCache(context).also { it.clearAll() }
        val source = Source()
        val repository = DefaultGalleryRepository(db, source, null, cache)
        try {
            repository.refresh(false)
            source.failMotion = true
            assertTrue(runCatching { repository.keepOriginalOffline("live") }.isFailure)
            assertNotEquals(MediaAvailability.ORIGINAL_CACHED, repository.observeGallery().first().single().availability)
            source.failMotion = false
            repository.keepOriginalOffline("live")
            assertEquals(1, source.stillDownloads)
            assertEquals(MediaAvailability.ORIGINAL_CACHED, repository.observeGallery().first().single().availability)
            source.offline = true
            repository.openPlayback("live", 1, 3).use { assertEquals("oti", it.input.reader().readText()) }
            val still = ByteArrayOutputStream(); repository.exportOriginal("live", still)
            val motion = ByteArrayOutputStream(); repository.exportOriginal("live", motion, true)
            assertEquals("still", still.toString())
            assertEquals("motion", motion.toString())
            source.offline = false
            repository.refresh(false)
            assertNotNull(repository.observeGallery().first().single().motionReference)
            repository.removeCachedOriginal("live")
            val media = repository.observeGallery().first().single()
            assertNull(media.originalReference)
            assertNull(media.motionReference)
        } finally { db.close(); cache.clearAll() }
    }

    @Test fun localEditsArePublishedOnlyAfterSourceConfirmsAndSmartCollectionsCannotBeEdited() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MelaDatabase::class.java).build()
        val cache = MediaFileCache(context)
        val source = Source()
        val repository = DefaultGalleryRepository(db, source, null, cache)
        try {
            repository.refresh(false)
            source.rejectEdit = true
            assertTrue(runCatching { repository.setFavorite("live", true) }.isFailure)
            assertFalse(repository.observeGallery().first().single().isFavorite)
            source.rejectEdit = false
            repository.setFavorite("live", true)
            assertTrue(repository.observeGallery().first().single().isFavorite)
            val album = repository.createAlbum("  Trip  ")
            assertEquals("Trip", album.name)
            repository.addToAlbum(album.id, listOf("live", "live"))
            assertTrue(album.id in repository.observeGallery().first().single().collectionIds)
            repository.renameAlbum(album.id, "Summer")
            assertEquals("Summer", repository.observeCollections().first().first { it.id == album.id }.name)
            assertTrue(runCatching { repository.renameAlbum(SmartCollection.LIVE_PHOTOS.id, "No") }.isFailure)
            source.rejectEdit = true
            assertTrue(runCatching { repository.deleteAlbum(album.id) }.isFailure)
            assertTrue(repository.observeCollections().first().any { it.id == album.id })
            source.rejectEdit = false
            repository.deleteAlbum(album.id)
            assertFalse(repository.observeCollections().first().any { it.id == album.id })
            val photo = repository.observeGallery().first().single()
            assertEquals("live", photo.id)
            assertTrue(photo.isFavorite)
            assertFalse(album.id in photo.collectionIds)
            assertTrue(runCatching { repository.deleteAlbum(SmartCollection.LIVE_PHOTOS.id) }.isFailure)
        } finally { db.close(); cache.clearAll() }
    }

    private class Source : CloudCatalogSource {
        override val accountLabel = "test"
        var failMotion = false
        var offline = false
        var rejectEdit = false
        var stillDownloads = 0
        override suspend fun fetchPage(cursor: String?, limit: Int) = CloudCatalogPage(listOf(
            RemoteMediaRecord("live", "live.heic", 10, 10, 10, "one", 0, 0, kind = MediaKind.LIVE_PHOTO, mimeType = "image/heic")
        ), null, "")
        override suspend fun collections() = CollectionSnapshot(listOf(GalleryCollection(SmartCollection.LIVE_PHOTOS.id, "Live Photos")),
            mapOf(SmartCollection.LIVE_PHOTOS.id to setOf("live")))
        override suspend fun writePreview(mediaId: String, output: OutputStream) = Unit
        override suspend fun writeOriginal(mediaId: String, output: OutputStream) {
            check(!offline); stillDownloads++; output.write("still".toByteArray())
        }
        override suspend fun writeMotion(mediaId: String, output: OutputStream) { check(!offline && !failMotion); output.write("motion".toByteArray()) }
        override suspend fun setFavorite(mediaId: String, favorite: Boolean) { check(!rejectEdit) }
        override suspend fun createAlbum(name: String) = GalleryCollection("new", name)
        override suspend fun deleteAlbum(id: String) { check(!rejectEdit) }
        override suspend fun renameAlbum(id: String, name: String) = Unit
        override suspend fun addToAlbum(id: String, mediaIds: List<String>) = Unit
    }
}
