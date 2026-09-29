package dev.mela.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.mela.app.ui.*
import dev.mela.app.ui.theme.MelaTheme
import dev.mela.engine.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class GalleryWorkflowComponentsTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<MelaApplication>()
    private fun text(id: Int) = app.getString(id)

    @Test fun albumPickerExcludesPhoneFoldersAndCreatesAnAlbumWithoutLeavingSelection() {
        var created: String? = null
        compose.setContent { MelaTheme(darkTheme = false) {
            AlbumChooser(listOf(GalleryCollection("device-folder:external:1", "Camera"),
                GalleryCollection("smart:videos", "Videos"), GalleryCollection("folder", "Trips", isFolder = true),
                GalleryCollection("album", "Summer")), dismiss = {}, create = { created = it }, choose = {})
        } }
        compose.onNodeWithText("Camera").assertDoesNotExist()
        compose.onNodeWithText("Trips").assertDoesNotExist()
        compose.onNodeWithText("Summer").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.new_album)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Family weekend")
        compose.onNodeWithText(text(R.string.save)).performClick()
        compose.runOnIdle { assertEquals("Family weekend", created) }
    }

    @Test fun reconnectRetriesTheSameVisibleTileAndDoesNotRedownloadRetainedPreviews() = runBlocking<Unit> {
        val graph = app.graph
        check(graph.accountManager.session.value == null)
        val item = GalleryMedia("fixture:reconnect", "reconnect.jpg", 1, 10, 10, MediaOrigin.ICLOUD,
            MediaAvailability.CLOUD_ONLY, null, null, 0, 0)
        val gallery = MutableStateFlow(listOf(item))
        val network = MutableStateFlow<NetworkStatus?>(NetworkStatus(false, false))
        val attempts = AtomicInteger()
        val repository = object : GalleryRepository by graph.galleryRepository {
            override fun observeGallery() = gallery
            override fun observeCollections() = flowOf(emptyList<GalleryCollection>())
            override suspend fun refresh(includeDeviceMedia: Boolean) = RefreshSummary(1, 0, 0)
            override suspend fun ensurePreview(mediaId: String) {
                attempts.incrementAndGet()
                if (network.value?.online != true) throw java.io.IOException("Offline")
                gallery.value = listOf(item.copy(previewReference = "retained-preview", availability = MediaAvailability.PREVIEW_CACHED))
            }
        }
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) { MelaViewModel(repository, graph.accountManager, graph.photos, graph.maintenance,
            savedState = SavedStateHandle(), network = network).also { store.put("reconnect", it) } }
        try {
            compose.setContent { val state by vm.uiState.collectAsState(); MelaTheme(darkTheme = false) {
                state.items.firstOrNull()?.let { media -> MediaTile(media = media, retryVersion = state.previewRetryVersion,
                    onClick = {}, onVisible = { vm.requestPreview(media.id) }) }
            } }
            compose.waitUntil(10_000) { attempts.get() >= 1 }
            network.value = NetworkStatus(true, false)
            compose.waitUntil(10_000) { gallery.value.single().previewReference != null }
            val completedAttempts = attempts.get()
            network.value = NetworkStatus(false, false)
            withTimeout(5_000) { vm.uiState.first { it.network?.online == false } }
            val version = vm.uiState.value.previewRetryVersion
            network.value = NetworkStatus(true, false)
            withTimeout(5_000) { vm.uiState.first { it.previewRetryVersion > version } }
            compose.waitForIdle()
            assertEquals(completedAttempts, attempts.get())
            compose.onNodeWithTag("media-fixture:reconnect").assertIsDisplayed()
        } finally { withContext(Dispatchers.Main) { store.clear() } }
    }

    @Test fun phoneStorageCleanupRequiresConfirmationAndKeepsCategoriesSeparate() {
        var removed: LocalMediaCategory? = null
        val state = GalleryUiState(phoneStorage = LocalMediaStorage(1024, 2048, 4096, 128L * 1024 * 1024))
        compose.setContent { MelaTheme(darkTheme = false) { PhoneStorageCard(state, LibraryActions(clearLocalMedia = { removed = it })) } }
        compose.onNodeWithTag("phone-storage-button").performClick()
        compose.onNodeWithTag("clear-storage-VIEWERS").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        assertNull(removed)
        compose.onNodeWithTag("clear-storage-VIEWERS").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-clear-storage").performClick()
        compose.runOnIdle { assertEquals(LocalMediaCategory.VIEWERS, removed) }
        compose.onNodeWithText(text(R.string.saved_thumbnails)).assertExists()
    }

    @Test fun failedVideoCanRetryInPlace() {
        val broken = java.util.concurrent.atomic.AtomicBoolean(true)
        val bytes = app.assets.open("fixture_video.mp4").use { it.readBytes() }
        compose.setContent { MelaTheme(darkTheme = true) {
            VideoPlayer("retry-clip", reader = { _, position, count ->
                if (broken.get()) throw java.io.IOException("Connection interrupted")
                val byteCount = if (count < 0) bytes.size - position.toInt() else minOf(count.toInt(), bytes.size - position.toInt())
                object : dev.mela.engine.source.MediaRead {
                    override val length = byteCount.toLong()
                    override val input = java.io.ByteArrayInputStream(bytes, position.toInt(), byteCount)
                    override fun close() = input.close()
                }
            }, modifier = Modifier.fillMaxSize())
        } }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("retry-video").fetchSemanticsNodes().isNotEmpty() }
        broken.set(false)
        compose.onNodeWithTag("retry-video").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, text(R.string.ready))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("retry-video").assertDoesNotExist()
    }

    @Test fun albumCreationIsNotRepeatedWhenAddingPhotosHasAnUncertainResult() = runBlocking<Unit> {
        val graph = app.graph
        check(graph.accountManager.session.value == null)
        val creates = AtomicInteger()
        val additions = AtomicInteger()
        val repository = object : GalleryRepository by graph.galleryRepository {
            override suspend fun refresh(includeDeviceMedia: Boolean) = RefreshSummary(0, 0, 0)
            override suspend fun createAlbum(name: String): GalleryCollection {
                creates.incrementAndGet(); return GalleryCollection("created-album", name)
            }
            override suspend fun addToAlbum(id: String, mediaIds: List<String>) {
                assertEquals("created-album", id); assertEquals(listOf("fixture:photo"), mediaIds)
                additions.incrementAndGet(); throw java.io.IOException("Response lost")
            }
        }
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) { MelaViewModel(repository, graph.accountManager, graph.photos, graph.maintenance,
            savedState = SavedStateHandle()).also { store.put("album", it) } }
        val observing = launch { vm.uiState.collect() }
        try {
            withTimeout(5_000) { vm.uiState.first { !it.isRefreshing } }
            withContext(Dispatchers.Main) { vm.createAlbumWithPhotos("Weekend", listOf("fixture:photo")) }
            val state = withTimeout(5_000) { vm.uiState.first { it.message?.resolve(app)?.contains("Weekend") == true } }
            assertEquals(app.getString(R.string.album_created_membership_uncertain, "Weekend"), state.message!!.resolve(app))
            assertEquals(1, creates.get()); assertEquals(1, additions.get())
        } finally { observing.cancelAndJoin(); withContext(Dispatchers.Main) { store.clear() } }
    }
}
