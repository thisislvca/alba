package dev.mela.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mela.engine.model.*
import dev.mela.protocol.account.ICloudAccountState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewQueueTest {
    @Test fun fastScrollReplacesObsoleteQueueAndKeepsInflightThumbnails() = runBlocking {
        val graph = ApplicationProvider.getApplicationContext<MelaApplication>().graph
        check(graph.accountManager.session.value == null) { "Preview queue tests require the demo account" }
        val items = List(120) { index -> GalleryMedia("fixture:queue:$index", "$index.jpg", index.toLong(),
            100, 100, MediaOrigin.ICLOUD, MediaAvailability.CLOUD_ONLY, null, null, 0, 0) }
        val gallery = MutableStateFlow(items)
        val started = MutableStateFlow<List<String>>(emptyList())
        val release = CompletableDeferred<Unit>()
        val repository = object : GalleryRepository by graph.galleryRepository {
            override fun observeGallery() = gallery
            override fun observeCollections() = flowOf(emptyList<GalleryCollection>())
            override suspend fun refresh(includeDeviceMedia: Boolean) = RefreshSummary(items.size, 0, 1)
            override suspend fun ensurePreview(mediaId: String) {
                started.value += mediaId
                release.await()
                gallery.value = gallery.value.map { if (it.id == mediaId) it.copy(
                    previewReference = "retained:$mediaId", availability = MediaAvailability.PREVIEW_CACHED) else it }
            }
        }
        val store = ViewModelStore()
        var collect: Job? = null
        try {
            val vm = withContext(Dispatchers.Main) {
                MelaViewModel(repository, graph.accountManager, graph.photos, graph.maintenance,
                    savedState = SavedStateHandle()).also { store.put("preview-test", it) }
            }
            collect = launch { vm.uiState.collect() }
            withTimeout(10_000) { vm.uiState.first { it.catalogItems.size == 120 && it.accountState == ICloudAccountState.Demo } }
            withContext(Dispatchers.Main) { vm.prefetchPreviews(items.take(48).map { it.id }) }
            withTimeout(5_000) { started.first { it.size == 4 } }
            withContext(Dispatchers.Main) {
                vm.prefetchPreviews(items.takeLast(48).map { it.id })
                release.complete(Unit)
            }
            withTimeout(10_000) { gallery.first { rows -> rows.takeLast(48).take(44).all { it.previewReference != null } } }
            assertEquals(48, started.value.size)
            assertEquals(items.take(4).map { it.id } + items.takeLast(48).take(44).map { it.id }, started.value)
            assertTrue(gallery.value.take(4).all { it.previewReference != null })
            // Scrolling back over retained thumbnails must not schedule new downloads.
            withTimeout(5_000) { vm.uiState.first { it.catalogItems.count { row -> row.previewReference != null } == 48 } }
            withContext(Dispatchers.Main) { vm.prefetchPreviews(items.takeLast(48).map { it.id }) }
            withTimeout(10_000) { gallery.first { rows -> rows.takeLast(48).all { it.previewReference != null } } }
            assertEquals(52, started.value.size)
        } finally {
            release.complete(Unit)
            collect?.cancelAndJoin()
            withContext(Dispatchers.Main) { store.clear() }
        }
    }
}
