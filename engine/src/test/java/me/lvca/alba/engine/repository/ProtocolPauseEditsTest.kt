package me.lvca.alba.engine.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.engine.cache.MediaFileCache
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.model.GalleryCollection
import me.lvca.alba.engine.model.PhotoCompanionException
import me.lvca.alba.engine.model.ProtocolArea
import me.lvca.alba.engine.model.ProtocolPause
import me.lvca.alba.engine.model.ProtocolStatusGate
import me.lvca.alba.engine.model.SharedAlbumGeneration
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.source.CloudCatalogPage
import me.lvca.alba.engine.source.CloudCatalogSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.OutputStream

/** A remote pause refuses the edits of its area before anything reaches iCloud, and only those. */
@RunWith(AndroidJUnit4::class)
class ProtocolPauseEditsTest {
    private lateinit var context: Context
    private lateinit var database: AlbaDatabase
    private lateinit var cache: MediaFileCache
    private val source = RecordingSource()
    private val status = object : ProtocolStatusGate {
        val current = MutableStateFlow<List<ProtocolPause>>(emptyList())
        override val pauses = current
        override suspend fun pauseFor(area: ProtocolArea) = current.value.firstOrNull { area in it.areas }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlbaDatabase::class.java).build()
        cache = MediaFileCache(context, availableBytes = { Long.MAX_VALUE })
    }

    @After
    fun tearDown() {
        runBlocking { cache.clearAll() }
        database.close()
    }

    private fun repository() = DefaultGalleryRepository(database, source, null, cache, protocolStatus = status)

    @Test
    fun aPausedAreaRefusesItsEditsAndChangesNothing() = runBlocking {
        status.current.value = listOf(ProtocolPause("edits", setOf(ProtocolArea.LIBRARY_EDITS)))
        val repository = repository()

        refused { repository.createAlbum("Trip") }
        refused { repository.renameAlbum("album", "Trip") }
        refused { repository.setCloudTrashed(listOf("icloud:account-a:asset"), trashed = true) }
        assertTrue("Nothing reached iCloud", source.calls.isEmpty())
        assertTrue("Nothing changed locally", repository.observeCollections().first().none { it.name == "Trip" })

        // Shared Albums are a different area, so they still work.
        repository.createSharedAlbum("Family", "operation", SharedAlbumGeneration.MODERN)
        assertEquals(listOf("createSharedAlbum"), source.calls)
    }

    @Test
    fun editsWorkAgainOnceThePauseClears() = runBlocking {
        status.current.value = listOf(ProtocolPause("shared", setOf(ProtocolArea.SHARED_ALBUMS)))
        val repository = repository()
        refused { repository.createSharedAlbum("Family", "operation", SharedAlbumGeneration.MODERN) }
        repository.createAlbum("Trip")

        status.current.value = emptyList()
        repository.createSharedAlbum("Family", "operation", SharedAlbumGeneration.MODERN)
        assertEquals(listOf("createAlbum", "createSharedAlbum"), source.calls)
    }

    private suspend fun refused(edit: suspend () -> Unit) {
        try {
            edit()
            fail("A paused edit ran")
        } catch (refusal: PhotoCompanionException.Refused) {
            assertEquals(StoredMessage.PAUSED_FOR_AN_ICLOUD_CHANGE, refusal.reason)
        }
    }

    private class RecordingSource : CloudCatalogSource {
        val calls = mutableListOf<String>()
        override val accountLabel = "account-a"
        override suspend fun fetchPage(cursor: String?, limit: Int) = CloudCatalogPage(emptyList(), null, "token")
        override suspend fun writePreview(mediaId: String, output: OutputStream) = Unit
        override suspend fun writeOriginal(mediaId: String, output: OutputStream) = Unit
        override suspend fun createAlbum(name: String): GalleryCollection {
            calls += "createAlbum"
            return GalleryCollection("album:$name", name)
        }
        override suspend fun renameAlbum(id: String, name: String) { calls += "renameAlbum" }
        override suspend fun setCloudTrashed(mediaIds: List<String>, trashed: Boolean) { calls += "setCloudTrashed" }
        override suspend fun createSharedAlbum(name: String, operationId: String, generation: SharedAlbumGeneration): GalleryCollection {
            calls += "createSharedAlbum"
            return GalleryCollection("shared:$name", name)
        }
    }
}
