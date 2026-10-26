package me.lvca.alba.app.shared

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.AlbaAppRule
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.Smoke
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.hideKeyboard
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.pressBack
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.testing.waitForVideoPlaying
import me.lvca.alba.engine.model.GalleryQuery
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaKind
import me.lvca.alba.engine.model.SharedAction
import me.lvca.alba.engine.model.SharedAlbumGeneration
import me.lvca.alba.engine.model.SharedCommand
import me.lvca.alba.engine.model.isShared
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Shared albums where the platform's media stack is involved: a shared video plays, streamed and from offline storage,
 * and contributions pass the platform's codec check on their video, which Robolectric does not provide.
 */
class SharedAlbumsFlowTest {
    @get:Rule val app = AlbaAppRule()
    private val compose get() = app.compose
    private val graph get() = app.graph
    private val repository get() = graph.galleryRepository

    private fun sharedAlbums() {
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-shared").performClick()
    }

    /** Opens the shared album named [name] from the shared albums page. */
    private fun openSharedAlbum(name: String) {
        val album = runBlocking { repository.observeCollections().first().single { it.name == name }.id }
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasTestTag("shared-album-$album"))
        compose.onNodeWithTag("shared-album-$album").performClick()
    }

    @Smoke @Test fun sharedVideoStreamsAndReopensFromOfflineStorage() {
        val id = "shared:demo-library:private:owner:two:video"
        sharedAlbums()
        openSharedAlbum("Family moments")
        compose.onNodeWithTag("media-$id").performClick()
        compose.waitForVideoPlaying()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        runBlocking { repository.keepOriginalOffline(id) }
        compose.onNodeWithTag("media-$id").performClick()
        compose.waitForVideoPlaying()
        assertEquals(MediaAvailability.ORIGINAL_CACHED, runBlocking { repository.observeGallery().first().first { it.id == id }.availability })
    }

    /** The photo in the album named [name], once a contribution has reached the catalog. */
    private fun contribution(name: String): String? = runBlocking {
        val album = repository.observeCollections().first().firstOrNull { it.name == name }
        album?.let { found -> repository.observeGallery().first().firstOrNull { found.id in it.collectionIds }?.id }
    }

    private fun label(id: Int) = app.activity.getString(id)

    /** Contributing a Live Photo or a video reads its codec with the platform's MediaExtractor. */
    @Test fun createsBothGenerationsContributesAndRestoresSharedDestination() {
        sharedAlbums()
        for (generation in listOf(SharedAlbumGeneration.MODERN, SharedAlbumGeneration.LEGACY)) {
            val name = "Android ${generation.name} test"
            compose.onNodeWithTag("collections-grid").performScrollToNode(hasTestTag("create-shared-album"))
            compose.onNodeWithTag("create-shared-album").performClick()
            compose.onNodeWithTag("shared-album-name").performTextInput(name)
            compose.hideKeyboard()
            compose.onNodeWithTag("shared-generation-$generation").performScrollTo().performClick()
            compose.onNodeWithTag("shared-album-save").performClick()
            // The new, empty album opens once the repository has made it.
            compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("shared-empty-add").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(name).assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(name).assertIsDisplayed()
            compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("shared-empty-add").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("shared-empty-add").performClick()
            compose.onNodeWithTag("shared-select-fixture:icloud:${if (generation == SharedAlbumGeneration.LEGACY) "0019" else "0020"}").performClick()
            compose.onNodeWithTag("shared-add-selected").performClick()
            compose.onNodeWithTag("shared-post-confirm").performClick()
            compose.waitUntil(UI_WAIT) { contribution(name) != null }
            val contributed = requireNotNull(contribution(name))
            compose.scrollToGalleryMedia(contributed)
            compose.onNodeWithTag("media-$contributed").performClick()
            compose.onNodeWithTag("media-pager").assertIsDisplayed()
            compose.openViewerDetails()
            compose.onNodeWithText(label(R.string.shared_copy_notice), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            compose.pressBack()
            compose.onNodeWithTag("floating-viewer-back").performClick()
            compose.waitUntil(SHORT_WAIT) { compose.onAllNodesWithTag("shared-version-badge").fetchSemanticsNodes().size == 1 }

            // The album's generation badge explains the role, also after recreation.
            compose.onNodeWithTag("shared-version-badge").performClick()
            compose.onNodeWithText(label(R.string.shared_role_owner)).assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(label(R.string.shared_role_owner)).assertIsDisplayed()
            compose.onNodeWithTag("shared-understood").performScrollTo().performClick()
            compose.pressBack()
        }
        val personal = runBlocking { GalleryQuery().apply(repository.observeGallery().first()) }
        assertFalse("Shared photos stay out of the personal library", personal.any { it.isShared })
        // Someone else's album that only allows viewing offers no way to add photos.
        openSharedAlbum("Family archive")
        compose.onNodeWithTag("album-add-media").assertDoesNotExist()
        compose.onNodeWithTag("shared-empty-add").assertDoesNotExist()
    }

    /** Contributing a video reads its codec with the platform's MediaExtractor, which Robolectric does not provide. */
    @Test fun selectedSharedBytesAndLegacySaveAreRealCopiesAndCoverPersists() {
        val owner = "shared:demo-library:private:owner:two"
        val cover = "$owner:photo-18"
        runBlocking {
            val album = repository.createSharedAlbum("Demo selected bytes", "demo-bytes", SharedAlbumGeneration.LEGACY)
            // Exercise the real repository staging and validation path, including the selected video bytes.
            repository.contributeToSharedAlbum(album.id, listOf("fixture:icloud:0019", "fixture:icloud:0001"), "selected")
            repository.refresh(false)
            val selected = graph.demoCatalog.sharedAlbums().records.filter { it.id.startsWith(album.id + ":") }
            assertEquals(2, selected.size)
            val video = selected.single { it.kind == MediaKind.VIDEO }
            val photo = selected.single { it.kind == MediaKind.PHOTO }
            assertEquals("Sea breeze.mp4", video.fileName)
            val original = ByteArrayOutputStream().also { repository.exportOriginal(video.id, it) }.toByteArray()
            assertArrayEquals(app.activity.assets.open("fixture_video.mp4").use { it.readBytes() }, original)
            repository.openPlayback(video.id, 8, 32).use { assertArrayEquals(original.copyOfRange(8, 40), it.input.readBytes()) }

            // Saving from a legacy shared album makes a real copy in the library.
            val before = graph.demoCatalog.fetchPage(null, 100).records.map { it.id }.toSet()
            repository.changeSharedAlbum(album.id, SharedCommand(SharedAction.SAVE_TO_LIBRARY, photo.id), "save-legacy")
            repository.refresh(false)
            val saved = graph.demoCatalog.fetchPage(null, 100).records.single { it.id !in before }
            val sourceBytes = ByteArrayOutputStream().also { repository.exportOriginal(photo.id, it) }.toByteArray()
            assertArrayEquals(sourceBytes, ByteArrayOutputStream().also { repository.exportOriginal(saved.id, it) }.toByteArray())

            repository.changeSharedAlbum(owner, SharedCommand(SharedAction.COVER, cover), "cover-device")
            repository.refresh(false)
            assertEquals(cover, repository.sharedManagement(owner).album.coverMediaId)
        }
        sharedAlbums()
        openSharedAlbum("Family moments")
        compose.onNodeWithTag("album-hero").assertIsDisplayed()
        assertNull(graph.accountManager.session.value)
        assertNull(graph.demoCatalog.writeAccountId)
    }
}
