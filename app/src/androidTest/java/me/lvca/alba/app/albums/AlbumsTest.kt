package me.lvca.alba.app.albums

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.LONG_WAIT
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.Smoke
import me.lvca.alba.app.testing.hideKeyboard
import me.lvca.alba.app.testing.openGalleryMedia
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.pressBack
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.testing.waitForGalleryMedia
import me.lvca.alba.engine.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AlbumsTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }
    private fun text(id: Int) = compose.activity.getString(id)

    @Smoke @Test fun favoriteEditingKeepsDetailOpenAndAlbumCreateRenameAddWork() {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithText("Favorites").performClick()
        compose.openGalleryMedia("fixture:icloud:0001")
        compose.openViewerDetails()
        compose.onNodeWithText("Unfavorite").performScrollTo().performClick()
        compose.waitUntil(SHORT_WAIT) { compose.onAllNodesWithText("Favorite").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Favorite").performScrollTo().performClick()
        compose.waitUntil(SHORT_WAIT) { compose.onAllNodesWithText("Unfavorite").fetchSemanticsNodes().isNotEmpty() }
        compose.pressBack()
        compose.onNodeWithContentDescription("Back to gallery").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.back_action)).performClick()
        compose.onNodeWithTag("collection-albums").performClick()
        compose.onNodeWithText("New album").performClick()
        compose.onNodeWithText("Album name").performTextInput("Extension test")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(SHORT_WAIT) { compose.onAllNodesWithContentDescription("Album options").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Album options").performClick()
        compose.onNodeWithText("Rename album").performClick()
        compose.onNodeWithText("Album name").performTextReplacement("Extension renamed")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(SHORT_WAIT) { compose.onAllNodesWithText("Extension renamed").fetchSemanticsNodes().isNotEmpty() }
        compose.hideKeyboard()
        compose.onNodeWithTag("tab-LIBRARY").performClick()
        compose.onNodeWithTag("tab-LIBRARY").assertIsSelected()
        compose.openGalleryMedia("fixture:icloud:0001")
        compose.openViewerDetails()
        compose.onNodeWithText("Add to album").performScrollTo().performClick()
        compose.onNodeWithText("Extension renamed").performClick()
        val graph = (compose.activity.application as AlbaApplication).graph
        compose.waitUntil(SHORT_WAIT) { runBlocking {
            val album = graph.galleryRepository.observeCollections().first().firstOrNull { it.name == "Extension renamed" }
            album != null && graph.galleryRepository.observeGallery().first().first { it.id == "fixture:icloud:0001" }.collectionIds.contains(album.id)
        } }
    }

    @Test fun creatingAnAlbumFromTheViewerAddsTheCurrentPhoto() {
        compose.waitForGalleryMedia()
        val repository = (compose.activity.application as AlbaApplication).graph.galleryRepository
        val name = "Workflow ${System.nanoTime()}"
        try {
            compose.openGalleryMedia("fixture:icloud:0016")
            compose.openViewerDetails()
            compose.onNodeWithText(text(R.string.add_to_album)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.new_album)).performClick()
            compose.onNode(hasSetTextAction()).performTextInput(name)
            compose.onNodeWithText(text(R.string.save)).performClick()
            compose.waitUntil(LONG_WAIT) { runBlocking {
                val album = repository.observeCollections().first().firstOrNull { it.name == name }
                album != null && repository.observeGallery().first().any { it.id == "fixture:icloud:0016" && album.id in it.collectionIds }
            } }
        } finally { runBlocking { repository.observeCollections().first().filter { it.name == name }.forEach { repository.deleteAlbum(it.id) } } }
    }

    @Test fun albumDeleteRequiresConfirmationAndKeepsLibraryPhotos() {
        compose.waitForGalleryMedia()
        val repository = (compose.activity.application as AlbaApplication).graph.galleryRepository
        // The demo catalog may still be loading, so the library can grow during the test; it must only never shrink.
        val before = runBlocking { repository.observeGallery().first().map { it.id } }
        val album = runBlocking {
            repository.createAlbum("Delete test").also { repository.addToAlbum(it.id, listOf("fixture:icloud:0001")) }
        }
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-albums").performClick()
        compose.onNodeWithText("Delete test").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Album options").performClick()
        compose.onNodeWithText("Delete album").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(runBlocking { repository.observeCollections().first().any { it.id == album.id } })
        compose.onNodeWithContentDescription("Album options").performClick()
        compose.onNodeWithText("Delete album").performClick()
        compose.onNodeWithText("Delete album", useUnmergedTree = true).performClick()
        compose.waitUntil(SHORT_WAIT) { runBlocking { repository.observeCollections().first().none { it.id == album.id } } }
        val after = runBlocking { repository.observeGallery().first().map { it.id }.toSet() }
        assertEquals("Deleting an album must keep its photos in the library", emptyList<String>(), before.filterNot(after::contains))
        assertTrue("fixture:icloud:0001" in after)
    }
}
