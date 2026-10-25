package me.lvca.alba.app.gallery.grid

import android.os.Looper
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.AlbaViewModel
import me.lvca.alba.app.platform.NetworkStatus
import me.lvca.alba.app.testing.LONG_WAIT
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.testDependencies
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.gallery.MediaTile
import me.lvca.alba.engine.model.GalleryCollection
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.GalleryRepository
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.engine.model.RefreshSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ThumbnailRetryTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<AlbaApplication>()

    @Test fun reconnectRetriesTheSameVisibleTileAndDoesNotRedownloadRetainedPreviews() {
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
        val vm = AlbaViewModel(testDependencies(graph).copy(repository = repository, network = network), SavedStateHandle())
        store.put("reconnect", vm)
        try {
            compose.setContent {
                val state by vm.gallery.state.collectAsState()
                // Keep the account state subscribed, as the app's screen does, so it follows the network.
                LaunchedEffect(vm) { vm.account.state.collect() }
                val retryVersion by vm.previews.retryVersion.collectAsState()
                AlbaTheme(darkTheme = false) {
                    state.items.firstOrNull()?.let { media ->
                        MediaTile(media = media, retryVersion = retryVersion, onClick = {}, onVisible = { vm.previews.request(media.id) })
                    }
                }
            }
            // The view model coalesces network changes with a delay on the main thread, which runs on Robolectric's clock.
            fun waitFor(timeout: Long, condition: () -> Boolean) = compose.waitUntil(timeout) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                condition()
            }
            waitFor(LONG_WAIT) { attempts.get() >= 1 }
            network.value = NetworkStatus(true, false)
            waitFor(LONG_WAIT) { gallery.value.single().previewReference != null }
            val completedAttempts = attempts.get()
            network.value = NetworkStatus(false, false)
            waitFor(SHORT_WAIT) { vm.account.state.value.network?.online == false }
            val version = vm.previews.retryVersion.value
            network.value = NetworkStatus(true, false)
            waitFor(SHORT_WAIT) { vm.previews.retryVersion.value > version }
            compose.waitForIdle()
            // The retry reaches the visible tile, but its preview is already retained, so nothing is downloaded again.
            assertEquals(completedAttempts, attempts.get())
            compose.onNodeWithTag("media-fixture:reconnect").assertIsDisplayed()
        } finally { store.clear() }
    }
}
