package dev.mela.engine.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.mela.engine.cache.MediaFileCache
import dev.mela.engine.database.MelaDatabase
import dev.mela.engine.model.*
import dev.mela.engine.source.*
import java.io.OutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SharedGalleryRepositoryTest {
    @Test fun firstRefreshStillPublishesPersonalPhotosWhenSharedServiceIsUnavailable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MelaDatabase::class.java).build()
        val cache = MediaFileCache(context)
        val source = Source().apply { offline = true }
        val repo = DefaultGalleryRepository(db, source, null, cache)
        try {
            assertTrue(repo.refresh(false).sharedAlbumsUnavailable)
            assertEquals("icloud:test:asset", repo.observeGallery().first().single().id)
            assertEquals("personal-album", repo.observeCollections().first().single().id)
            source.offline = false
            assertFalse(repo.refresh(false).sharedAlbumsUnavailable)
            assertEquals(2, repo.observeGallery().first().size)
        } finally { cache.clearAll(); db.close() }
    }
    @Test fun offlineSharedCopiesSurviveErrorsAndNeverEnterPersonalChangesOrEdits() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MelaDatabase::class.java).build()
        val cache = MediaFileCache(context).also { it.clearAll() }
        val source = Source()
        val repo = DefaultGalleryRepository(db, source, null, cache)
        try {
            repo.refresh(false)
            val initial = repo.observeGallery().first()
            assertEquals(2, initial.size)
            assertEquals(listOf("icloud:test:asset"), GalleryQuery().apply(initial).map { it.id })
            assertEquals(1, GalleryQuery(collectionId = Source.ALBUM).apply(initial).size)
            repo.keepOriginalOffline(Source.SHARED)
            source.offline = true
            source.personalName = "Updated personal photo.jpg"
            assertTrue(repo.refresh(false).sharedAlbumsUnavailable)
            assertEquals(source.personalName, repo.observeGallery().first().first { it.id == "icloud:test:asset" }.fileName)
            var media = repo.observeGallery().first().first { it.id == Source.SHARED }
            assertEquals(MediaAvailability.ORIGINAL_CACHED, media.availability)
            assertEquals(SharedAlbumGeneration.LEGACY, repo.observeCollections().first().first { it.id == Source.ALBUM }.shared!!.generation)
            assertTrue(runCatching { repo.setFavorite(Source.SHARED, true) }.isFailure)
            assertTrue(runCatching { repo.setCloudTrashed(listOf(Source.SHARED), true) }.isFailure)
            assertTrue(runCatching { repo.addToAlbum("personal-album", listOf(Source.SHARED)) }.isFailure)
            assertTrue(runCatching { repo.deleteAlbum(Source.ALBUM) }.isFailure)
            assertEquals(0, source.edits)
            source.offline = false
            repo.refresh(false)
            assertFalse(source.known.any { SharedMediaIdentity.isShared(it.id) })
            media = repo.observeGallery().first().first { it.id == Source.SHARED }
            assertEquals(MediaAvailability.ORIGINAL_CACHED, media.availability)
            val path = requireNotNull(media.originalReference)
            source.revoked = true
            repo.refresh(false)
            assertFalse(repo.observeGallery().first().any { it.id == Source.SHARED })
            assertFalse(java.io.File(path).exists())
        } finally { cache.clearAll(); db.close() }
    }
    @Test fun directPhoneContributionStagesValidatedOriginalWithoutPersonalBackupProof() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MelaDatabase::class.java).build()
        val cache = MediaFileCache(context).also { it.clearAll() }
        val image = android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().also { image.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,it) }.toByteArray()
        image.recycle()
        val source = Source().apply { contributor = true }
        val device = object : DeviceCatalogSource {
            override suspend fun scanImages() = listOf(DeviceMediaRecord("device:test","phone.jpg",1000,8,8,"content://media/external/images/media/1","revision",byteCount=bytes.size.toLong()))
            override suspend fun writeOriginal(contentUri: String, output: OutputStream): DeviceOriginalEvidence {
                assertEquals("content://media/external/images/media/1",contentUri); output.write(bytes)
                return DeviceOriginalEvidence(bytes.size.toLong(),"unused")
            }
        }
        val repo = DefaultGalleryRepository(db, source, device, cache)
        try {
            repo.refresh(true)
            repo.contributeToSharedAlbum(Source.ALBUM,listOf("device:test"),java.util.UUID.randomUUID().toString())
            assertArrayEquals(bytes,source.uploaded)
            assertTrue(repo.observeGallery().first().any { it.id == "device:test" })
            assertEquals(1,repo.observeGallery().first().count { it.isShared })
            assertEquals(0,source.edits)
        } finally { cache.clearAll(); db.close() }
    }

    private class Source : CloudCatalogSource {
        var contributor = false; var uploaded = byteArrayOf();
        var offline = false; var revoked = false; var edits = 0; var known = emptyList<RemoteMediaRecord>(); var personalName = "same.jpg"
        override val accountLabel = "test"
        private fun record(id: String) = RemoteMediaRecord(id, if(id == "icloud:test:asset") personalName else "same.jpg", 1000, 640, 480, "m:a", 0, 0,
            masterRecordName = "master", assetRecordName = "asset", resourceFingerprint = "bytes")
        override suspend fun fetchPage(cursor: String?, limit: Int) = CloudCatalogPage(listOf(record("icloud:test:asset")), null, "token")
        override suspend fun captureSyncToken() = "token"
        override suspend fun changes(token: String, known: List<RemoteMediaRecord>): CloudChanges {
            this.known = known
            return CloudChanges(listOf(record("icloud:test:asset")), emptySet(), "token", false)
        }
        override suspend fun collections() = CollectionSnapshot(listOf(GalleryCollection("personal-album", "Personal")), emptyMap())
        override suspend fun sharedAlbums(): SharedCatalogSnapshot {
            if(offline) throw java.io.IOException("offline")
            if(revoked) return SharedCatalogSnapshot(emptyList(), CollectionSnapshot(emptyList(), emptyMap()))
            return SharedCatalogSnapshot(listOf(record(SHARED)), CollectionSnapshot(listOf(GalleryCollection(ALBUM, "Family",
                shared = SharedAlbumInfo(SharedAlbumGeneration.LEGACY, if(contributor) SharedAlbumRole.CONTRIBUTOR else SharedAlbumRole.VIEWER))), mapOf(ALBUM to setOf(SHARED))))
        }
        override suspend fun uploadSharedPhoto(id: String, fileName: String, source: OneShotUploadSource, operationId: String) {
            assertEquals(ALBUM,id); assertEquals("phone.jpg",fileName)
            uploaded = source.openOnce().use { it.readBytes() }
            assertEquals(source.byteCount,uploaded.size.toLong())
            assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest(uploaded).joinToString("") { "%02x".format(it) }, source.sha256Hex)
            assertTrue(runCatching { source.openOnce() }.isFailure)
        }
        override suspend fun setFavorite(mediaId: String, favorite: Boolean) { edits++ }
        override suspend fun setCloudTrashed(mediaIds: List<String>, trashed: Boolean) { edits++ }
        override suspend fun addToAlbum(id: String, mediaIds: List<String>) { edits++ }
        override suspend fun deleteAlbum(id: String) { edits++ }
        override suspend fun writePreview(mediaId: String, output: OutputStream) { output.write(byteArrayOf(1)) }
        override suspend fun writeOriginal(mediaId: String, output: OutputStream) { output.write(byteArrayOf(1, 2, 3)) }
        companion object { const val ALBUM = "shared:test:legacy:owner:album"; const val SHARED = "$ALBUM:asset" }
    }
}
