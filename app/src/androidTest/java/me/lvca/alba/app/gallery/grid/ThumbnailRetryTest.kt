package me.lvca.alba.app.gallery.grid

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import me.lvca.alba.engine.model.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.AlbaViewModel
import me.lvca.alba.app.platform.NetworkStatus
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.LONG_WAIT
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.testDependencies
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.gallery.MediaTile

class ThumbnailRetryTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createComposeRule()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }
    private val app get() = ApplicationProvider.getApplicationContext<AlbaApplication>()

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
        val vm = withContext(Dispatchers.Main) { AlbaViewModel(testDependencies(graph).copy(repository = repository, network = network),
            SavedStateHandle()).also { store.put("reconnect", it) } }
        try {
            compose.setContent { val gallery by vm.gallery.state.collectAsState(); val retryVersion by vm.previews.retryVersion.collectAsState(); AlbaTheme(darkTheme = false) {
                gallery.items.firstOrNull()?.let { media -> MediaTile(media = media, retryVersion = retryVersion,
                    onClick = {}, onVisible = { vm.previews.request(media.id) }) }
            } }
            compose.waitUntil(LONG_WAIT) { attempts.get() >= 1 }
            network.value = NetworkStatus(true, false)
            compose.waitUntil(LONG_WAIT) { gallery.value.single().previewReference != null }
            val completedAttempts = attempts.get()
            network.value = NetworkStatus(false, false)
            withTimeout(SHORT_WAIT) { vm.account.state.first { it.network?.online == false } }
            val version = vm.previews.retryVersion.value
            network.value = NetworkStatus(true, false)
            withTimeout(SHORT_WAIT) { vm.previews.retryVersion.first { it > version } }
            compose.waitForIdle()
            assertEquals(completedAttempts, attempts.get())
            compose.onNodeWithTag("media-fixture:reconnect").assertIsDisplayed()
        } finally { withContext(Dispatchers.Main) { store.clear() } }
    }
}
