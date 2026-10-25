package me.lvca.alba.app.shared

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.Smoke
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.hideKeyboard
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.pressBack
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.testing.waitForVideoPlaying
import me.lvca.alba.engine.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Full native navigation and repository wiring, using the explicitly labelled demo account. */
class SharedAlbumsFlowTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }
    private val repository get() = (compose.activity.application as AlbaApplication).graph.galleryRepository
    private fun label(id: Int) = compose.activity.getString(id)
    private val graph get() = (compose.activity.application as AlbaApplication).graph
    private fun sharedAlbums() {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-shared").performClick()
    }
    @Smoke @Test fun sharedVideoStreamsAndReopensFromOfflineStorage() {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-shared").performClick()
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasText("Family moments"))
        compose.onNodeWithText("Family moments").performClick()
        val id = "shared:demo-library:private:owner:two:video"
        compose.onNodeWithTag("media-$id").performClick()
        compose.waitForVideoPlaying()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        runBlocking { repository.keepOriginalOffline(id) }
        compose.waitUntil(SHORT_WAIT) { compose.onAllNodesWithTag("media-$id").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("media-$id").performClick()
        compose.waitForVideoPlaying()
        assertEquals(MediaAvailability.ORIGINAL_CACHED, runBlocking { repository.observeGallery().first().first { it.id == id }.availability })
    }

    @Test fun createsBothGenerationsContributesAndRestoresSharedDestination() {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-shared").performClick()
        for (generation in listOf(SharedAlbumGeneration.MODERN, SharedAlbumGeneration.LEGACY)) {
            compose.onNodeWithTag("collections-grid").performScrollToNode(hasTestTag("create-shared-album"))
            compose.onNodeWithTag("create-shared-album").performClick()
            val name = "Android ${generation.name} test"
            compose.onNodeWithTag("shared-album-name").performTextInput(name)
            compose.hideKeyboard()
            compose.onNodeWithTag("shared-generation-$generation").performScrollTo().performClick()
            compose.onNodeWithText(label(R.string.save)).performClick()
            compose.waitUntil(UI_WAIT) { compose.onAllNodesWithContentDescription(label(R.string.shared_add_photos)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(name).assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(name).assertIsDisplayed()
            compose.onNodeWithContentDescription(label(R.string.shared_add_photos)).performClick()
            compose.onNodeWithTag("shared-select-fixture:icloud:${if (generation == SharedAlbumGeneration.LEGACY) "0019" else "0020"}").performClick()
            compose.onNodeWithText(compose.activity.resources.getQuantityString(R.plurals.shared_add_selected, 1, 1)).performClick()
            var contributedId: String? = null
            compose.waitUntil(UI_WAIT) {
                runBlocking {
                    val album = repository.observeCollections().first().firstOrNull { it.name == name }
                    contributedId = album?.let { found -> repository.observeGallery().first().firstOrNull { found.id in it.collectionIds }?.id }
                    contributedId != null
                }
            }
            // A catalog commit can precede the new item reaching the Compose grid.
            compose.scrollToGalleryMedia(requireNotNull(contributedId))
            compose.onNodeWithTag("media-$contributedId").assertIsDisplayed().performClick()
            compose.onNodeWithTag("media-pager").assertIsDisplayed()
            compose.openViewerDetails()
            compose.onNodeWithTag("photo-details-panel").assertIsDisplayed()
            compose.onNodeWithText(label(R.string.shared_copy_notice), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            compose.pressBack()
            compose.onNodeWithContentDescription(label(R.string.back_to_gallery)).performClick()
            compose.waitUntil(SHORT_WAIT) { compose.onAllNodesWithTag("shared-version-badge").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("shared-version-badge").performClick()
            compose.onNodeWithText(label(R.string.shared_role_owner)).assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(label(R.string.shared_role_owner)).assertIsDisplayed()
            compose.onNodeWithText(label(R.string.shared_understood)).performScrollTo().performClick()
            compose.onNodeWithContentDescription(label(R.string.back_action)).performClick()
        }
        val personal = runBlocking { GalleryQuery().apply(repository.observeGallery().first()) }
        assertFalse(personal.any { it.isShared })
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasText("Family archive"))
        compose.onNodeWithText("Family archive").performClick()
        compose.onNodeWithContentDescription(label(R.string.shared_add_photos)).assertDoesNotExist()
    }

    @Test fun selectedSharedBytesAndLegacySaveAreRealCopiesAndCoverPersists() = runBlocking {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        val album = repository.createSharedAlbum("Demo selected bytes", "demo-bytes", SharedAlbumGeneration.LEGACY)
        // Exercise the real repository staging/validation path, including the selected video bytes.
        repository.contributeToSharedAlbum(album.id, listOf("fixture:icloud:0019", "fixture:icloud:0001"), "selected")
        repository.refresh(false)
        val snapshot = graph.demoCatalog.sharedAlbums()
        val selected = snapshot.records.filter { it.id.startsWith(album.id + ":") }
        assertEquals(2, selected.size)
        val video = selected.single { it.kind == MediaKind.VIDEO }
        val photo = selected.single { it.kind == MediaKind.PHOTO }
        assertEquals("Sea breeze.mp4", video.fileName)
        val original = ByteArrayOutputStream().also { repository.exportOriginal(video.id, it) }.toByteArray()
        assertArrayEquals(compose.activity.assets.open("fixture_video.mp4").use { it.readBytes() }, original)
        repository.openPlayback(video.id, 8, 32).use { assertArrayEquals(original.copyOfRange(8, 40), it.input.readBytes()) }
        val before = graph.demoCatalog.fetchPage(null, 100).records.map { it.id }.toSet()
        repository.changeSharedAlbum(album.id, SharedCommand(SharedAction.SAVE_TO_LIBRARY, photo.id), "save-legacy")
        repository.refresh(false)
        val saved = graph.demoCatalog.fetchPage(null, 100).records.single { it.id !in before }
        val sourceBytes = ByteArrayOutputStream().also { repository.exportOriginal(photo.id, it) }.toByteArray()
        assertArrayEquals(sourceBytes, ByteArrayOutputStream().also { repository.exportOriginal(saved.id, it) }.toByteArray())
        val owner = "shared:demo-library:private:owner:two"
        val cover = "$owner:photo-18"
        repository.changeSharedAlbum(owner, SharedCommand(SharedAction.COVER, cover), "cover-device")
        repository.refresh(false)
        assertEquals(cover, repository.sharedManagement(owner).album.coverMediaId)
        sharedAlbums()
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasText("Family moments"))
        compose.onNodeWithText("Family moments").performClick()
        compose.onNodeWithTag("album-hero").assertIsDisplayed()
        assertNull(graph.accountManager.session.value)
        assertNull(graph.demoCatalog.writeAccountId)
    }
}
