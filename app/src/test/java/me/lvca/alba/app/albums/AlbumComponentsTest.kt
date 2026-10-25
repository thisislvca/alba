package me.lvca.alba.app.albums

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.AlbaViewModel
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.runBlockingWithMainLooper
import me.lvca.alba.app.testing.testDependencies
import me.lvca.alba.app.ui.collections.AlbumChooser
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.engine.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AlbumComponentsTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<AlbaApplication>()
    private fun text(id: Int) = app.getString(id)

    @Test fun albumPickerOffersOnlyPersonalAlbumsAndCreatesOneWithoutLeavingSelection() {
        var created: String? = null
        compose.setContent { AlbaTheme(darkTheme = false) {
            AlbumChooser(listOf(GalleryCollection("device-folder:external:1", "Camera"),
                GalleryCollection("smart:videos", "Videos"), GalleryCollection("folder", "Trips", isFolder = true),
                GalleryCollection("album", "Summer"), GalleryCollection("shared:test:x:y:z", "Shared",
                    shared = SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.OWNER))),
                dismiss = {}, create = { created = it }, choose = {})
        } }
        compose.onNodeWithText("Camera").assertDoesNotExist()
        compose.onNodeWithText("Trips").assertDoesNotExist()
        compose.onNodeWithText("Shared").assertDoesNotExist()
        compose.onNodeWithText("Summer").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.new_album)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Family weekend")
        compose.onNodeWithText(text(R.string.save)).performClick()
        compose.runOnIdle { assertEquals("Family weekend", created) }
    }

    @Test fun albumCreationIsNotRepeatedWhenAddingPhotosHasAnUncertainResult() = runBlockingWithMainLooper<Unit> {
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
        val vm = withContext(Dispatchers.Main) { AlbaViewModel(testDependencies(graph).copy(repository = repository),
            SavedStateHandle()).also { store.put("album", it) } }
        val observing = launch { vm.account.state.collect() }
        try {
            withTimeout(SHORT_WAIT) { vm.account.state.first { !it.isRefreshing } }
            withContext(Dispatchers.Main) { vm.edits.createAlbumWithPhotos("Weekend", listOf("fixture:photo")) }
            val message = withTimeout(SHORT_WAIT) { vm.message.first { it?.resolve(app)?.contains("Weekend") == true } }
            assertEquals(app.getString(R.string.album_created_membership_uncertain, "Weekend"), message!!.resolve(app))
            assertEquals(1, creates.get()); assertEquals(1, additions.get())
        } finally { observing.cancelAndJoin(); withContext(Dispatchers.Main) { store.clear() } }
    }
}
