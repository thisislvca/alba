package dev.mela.engine.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.mela.engine.database.*
import dev.mela.engine.model.*
import dev.mela.engine.source.*
import dev.mela.engine.cache.MediaFileCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GalleryDisplaySafetyTest {
    @Test fun deviceOnlyRefreshWorksWithoutACloudConnectionAndCloudEditsPublishAfterConfirmation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MelaDatabase::class.java).build()
        val cache = MediaFileCache(context)
        var cloudReads = 0; var reject = false; var trashed = false; var membership = true
        val cloud = object : CloudCatalogSource {
            override val accountLabel = "test"
            private fun photo() = RemoteMediaRecord("cloud", "one.jpg", 1, 20, 20, "one", 0, 0, isTrashed = trashed)
            override suspend fun fetchPage(cursor: String?, limit: Int): CloudCatalogPage { cloudReads++; return CloudCatalogPage(if (trashed) emptyList() else listOf(photo()), null, "") }
            override suspend fun recentlyDeleted() = if (trashed) listOf(photo()) else emptyList()
            override suspend fun collections() = CollectionSnapshot(listOf(GalleryCollection("album", "Album")), mapOf("album" to if (membership) setOf("cloud") else emptySet()))
            override suspend fun removeFromAlbum(id: String, mediaIds: List<String>) { check(!reject); membership = false }
            override suspend fun setCloudTrashed(mediaIds: List<String>, trashed: Boolean) { check(!reject); setTrash(trashed) }
            private fun setTrash(value: Boolean) { trashed = value }
            override suspend fun writePreview(mediaId: String, output: java.io.OutputStream) { output.write(1) }
            override suspend fun writeOriginal(mediaId: String, output: java.io.OutputStream) { output.write(2) }
        }
        val device = object : DeviceCatalogSource {
            override suspend fun scanImages() = listOf(DeviceMediaRecord("device", "local.jpg", 1, 20, 20, "content://media/external/images/media/1", "revision"))
            override suspend fun writeOriginal(contentUri: String, output: java.io.OutputStream) = error("unused")
        }
        val repository = DefaultGalleryRepository(db, cloud, device, cache)
        try {
            repository.refreshDevice()
            assertEquals(0, cloudReads)
            assertEquals("device", repository.observeGallery().first().single().id)
            repository.refresh(true); repository.keepOriginalOffline("cloud")
            reject = true
            assertTrue(runCatching { repository.removeFromAlbum("album", listOf("cloud")) }.isFailure)
            assertTrue(runCatching { repository.setCloudTrashed(listOf("cloud"), true) }.isFailure)
            var item = repository.observeGallery().first().first { it.id == "cloud" }
            assertFalse(item.isTrashed); assertTrue("album" in item.collectionIds)
            reject = false
            repository.removeFromAlbum("album", listOf("cloud")); repository.setCloudTrashed(listOf("cloud"), true); repository.refresh(true)
            item = repository.observeGallery().first().first { it.id == "cloud" }
            assertTrue(item.isTrashed); assertFalse("album" in item.collectionIds)
            assertNotNull(item.originalReference)
            repository.setCloudTrashed(listOf("cloud"), false); repository.refresh(true)
            assertFalse(repository.observeGallery().first().first { it.id == "cloud" }.isTrashed)
        } finally { db.close(); cache.clearAll() }
    }
    @Test fun viewerCacheIsTemporaryAndLocalFavoritesSurviveRefreshAndSignout() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MelaDatabase::class.java).build()
        val cache = MediaFileCache(context)
        val cloud = object : CloudCatalogSource {
            override val accountLabel = "test"
            override suspend fun fetchPage(cursor: String?, limit: Int) = CloudCatalogPage(listOf(RemoteMediaRecord("cloud", "one.jpg", 1, 20, 20, "one", 0, 0)), null, "")
            override suspend fun writePreview(mediaId: String, output: java.io.OutputStream) { output.write(byteArrayOf(1)) }
            override suspend fun writeOriginal(mediaId: String, output: java.io.OutputStream) { output.write(byteArrayOf(2, 3)) }
        }
        var deviceVisible = true
        val device = object : DeviceCatalogSource {
            override suspend fun scanImages() = if (!deviceVisible) emptyList() else listOf(DeviceMediaRecord("device", "local.jpg", 1, 20, 20, "content://media/external/images/media/1", "revision", deviceFolderId="device-folder:camera", deviceFolderName="Camera"))
            override suspend fun writeOriginal(contentUri: String, output: java.io.OutputStream) = error("unused")
        }
        val repository = DefaultGalleryRepository(db, cloud, device, cache)
        try {
            repository.refresh(true); repository.ensurePreview("cloud"); repository.ensureViewer("cloud")
            val displayed = repository.observeGallery().first().first { it.id == "cloud" }
            assertNotNull(displayed.viewerReference)
            assertEquals(MediaAvailability.PREVIEW_CACHED, displayed.availability)
            assertNull(displayed.originalReference)
            repository.setFavorite("device", true); repository.refresh(true)
            assertTrue(repository.observeGallery().first().first { it.id == "device" }.isFavorite)
            deviceVisible = false; repository.refresh(true)
            assertFalse(repository.observeGallery().first().any { it.id == "device" })
            deviceVisible = true; repository.refresh(true)
            assertTrue(repository.observeGallery().first().first { it.id == "device" }.isFavorite)
            repository.clearCloudCatalog()
            assertTrue(repository.observeGallery().first().single().isFavorite)
        } finally { db.close(); cache.clearAll() }
    }
    @Test fun displayLinksRejectChangedSourcesInvalidProofsAndInactiveAccounts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MelaDatabase::class.java).build()
        try {
            val sql = db.openHelper.writableDatabase
            // Seed all required fields; only explicit proof relationships may collapse the display.
            fun seed(table: String, values: Map<String, Any?>) {
                val columns = sql.query("PRAGMA table_info($table)").use { c -> buildList {
                    while(c.moveToNext()) add(Triple(c.getString(1), c.getString(2), c.getInt(3)))
                } }
                val args = columns.map { (name,type,required) -> if (values.containsKey(name)) values[name] else if (required == 0) null else if(type == "INTEGER") 0 else "" }
                sql.execSQL("INSERT INTO $table (${columns.joinToString { it.first }}) VALUES (${columns.joinToString { "?" }})",args.toTypedArray())
            }
            seed("accounts", mapOf("accountId" to "a", "active" to 1))
            seed("catalog_items", mapOf("mediaId" to "local", "origin" to "DEVICE", "sourceRevision" to "local-rev", "isTrashed" to 0))
            seed("catalog_items", mapOf("mediaId" to "cloud", "origin" to "ICLOUD", "masterRecordName" to "master", "assetRecordName" to "asset", "sourceRevision" to "m1:a1"))
            seed("remote_asset_pairs", mapOf("remotePairId" to "pair", "accountId" to "a", "databaseScope" to "private", "zoneName" to "PrimarySync", "masterRecordName" to "master", "assetRecordName" to "asset", "relationIsCurrent" to 1))
            seed("asset_links", mapOf("linkId" to "link", "accountId" to "a", "status" to "CURRENT"))
            seed("verification_proofs", mapOf("proofId" to "proof", "linkId" to "link", "remotePairId" to "pair", "accountId" to "a", "mediaId" to "local", "sourceRevision" to "local-rev", "masterChangeTag" to "m1", "assetChangeTag" to "a1"))
            assertEquals(1, db.catalogDao().observeDisplayLinks().first().size)
            seed("catalog_items", mapOf("mediaId" to "shared:a:private:owner:album:asset", "origin" to "ICLOUD", "masterRecordName" to "master", "assetRecordName" to "asset", "sourceRevision" to "m1:a1"))
            assertEquals(listOf("cloud"), db.catalogDao().observeDisplayLinks().first().map { it.cloudId })
            sql.execSQL("UPDATE remote_asset_pairs SET zoneName='SharedCollection-test'")
            assertTrue(db.catalogDao().observeDisplayLinks().first().isEmpty())
            sql.execSQL("UPDATE remote_asset_pairs SET zoneName='PrimarySync', databaseScope='shared'")
            assertTrue(db.catalogDao().observeDisplayLinks().first().isEmpty())
            sql.execSQL("UPDATE remote_asset_pairs SET databaseScope='private'")
            sql.execSQL("UPDATE catalog_items SET isTrashed=1 WHERE mediaId='cloud'")
            assertTrue(db.catalogDao().observeDisplayLinks().first().isEmpty())
            sql.execSQL("UPDATE catalog_items SET isTrashed=0 WHERE mediaId='cloud'")
            sql.execSQL("UPDATE catalog_items SET sourceRevision='changed' WHERE mediaId='local'")
            assertTrue(db.catalogDao().observeDisplayLinks().first().isEmpty())
            sql.execSQL("UPDATE catalog_items SET sourceRevision='local-rev' WHERE mediaId='local'")
            sql.execSQL("UPDATE verification_proofs SET invalidatedAtEpochMillis=1")
            assertTrue(db.catalogDao().observeDisplayLinks().first().isEmpty())
            sql.execSQL("UPDATE verification_proofs SET invalidatedAtEpochMillis=NULL")
            sql.execSQL("UPDATE catalog_items SET sourceRevision='m2:a1' WHERE mediaId='cloud'")
            assertTrue(db.catalogDao().observeDisplayLinks().first().isEmpty())
            sql.execSQL("UPDATE catalog_items SET sourceRevision='m1:a1' WHERE mediaId='cloud'")
            sql.execSQL("UPDATE accounts SET active=0")
            assertTrue(db.catalogDao().observeDisplayLinks().first().isEmpty())
        } finally { db.close() }
    }
}
