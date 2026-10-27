package me.lvca.alba.app.albums

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.AlbaAppRule
import me.lvca.alba.app.testing.hideKeyboard
import me.lvca.alba.app.testing.openGalleryMedia
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.pressBack
import me.lvca.alba.app.testing.waitUntilSettled
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Favorites from the viewer, and making, renaming and filling a personal album. */
@RunWith(AndroidJUnit4::class)
class AlbumsTest {
    @get:Rule val app = AlbaAppRule()
    private val compose get() = app.compose
    private val repository get() = app.application.graph.galleryRepository

    /** Whether the album named [name] exists and holds the demo's first photo. Room reads need no main looper. */
    private fun albumHoldsFirstPhoto(name: String) = runBlocking {
        val album = repository.observeCollections().first().firstOrNull { it.name == name }
        album != null && repository.observeGallery().first().any { it.id == "fixture:icloud:0001" && album.id in it.collectionIds }
    }

    @Test fun favoriteEditingKeepsDetailOpenAndAlbumCreateRenameAddWork() {
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-favorites").performClick()
        compose.openGalleryMedia("fixture:icloud:0001")
        compose.openViewerDetails()
        // Unfavoriting keeps the photo and its details open, even in Favorites; favoriting it again shows at once.
        val favorite = compose.onNodeWithTag("detail-favorite")
        favorite.assertTextEquals(app.string(R.string.unfavorite)).performScrollTo().performClick()
        compose.waitUntilSettled { runCatching { favorite.assertTextEquals(app.string(R.string.favorite)) }.isSuccess }
        compose.onNodeWithTag("photo-details-panel").assertIsDisplayed()
        favorite.performScrollTo().performClick()
        compose.waitUntilSettled { runCatching { favorite.assertTextEquals(app.string(R.string.unfavorite)) }.isSuccess }
        compose.pressBack()
        compose.onNodeWithTag("floating-viewer-back").performClick()
        compose.pressBack()

        // A new album, renamed from its own page.
        compose.onNodeWithTag("collection-albums").performClick()
        compose.onNodeWithTag("new-album").performClick()
        compose.onNodeWithTag("album-name-field").performTextInput("Extension test")
        compose.onNodeWithTag("album-name-save").performClick()
        compose.waitUntilSettled { compose.onAllNodes(hasText("Extension test")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("album-options").performClick()
        compose.onNodeWithTag("album-rename").performClick()
        compose.onNodeWithTag("album-name-field").performTextReplacement("Extension renamed")
        compose.onNodeWithTag("album-name-save").performClick()
        compose.waitUntilSettled { compose.onAllNodes(hasText("Extension renamed")).fetchSemanticsNodes().isNotEmpty() }
        compose.hideKeyboard()

        // Adding a photo to it from the viewer.
        compose.onNodeWithTag("tab-LIBRARY").performClick()
        compose.onNodeWithTag("tab-LIBRARY").assertIsSelected()
        compose.openGalleryMedia("fixture:icloud:0001")
        compose.openViewerDetails()
        compose.onNodeWithTag("detail-add-to-album").performScrollTo().performClick()
        val renamed = runBlocking { repository.observeCollections().first().single { it.name == "Extension renamed" } }
        compose.onNodeWithTag("album-chooser-${renamed.id}").performClick()
        compose.waitUntilSettled { albumHoldsFirstPhoto("Extension renamed") }
        // The repository holds the photo before the action has finished: the details stay disabled while it runs, and a tap
        // on a disabled row does nothing. Wait until the action has ended, which is when the row is enabled again.
        compose.waitUntilSettled { runCatching { compose.onNodeWithTag("detail-add-to-album").assertIsEnabled() }.isSuccess }

        // A new album made from the viewer's chooser starts with the photo on screen.
        // Let the "Added to album" message time out on the test clock: it sits over the bottom of the details, where
        // the row lands after scrolling, and a tap there would reach the message instead.
        compose.mainClock.advanceTimeBy(MESSAGE_TIMEOUT_MILLIS)
        compose.onNodeWithTag("detail-add-to-album").performScrollTo().performClick()
        compose.onNodeWithTag("album-chooser-new").performClick()
        compose.onNodeWithTag("album-name-field").performTextInput("From the viewer")
        compose.onNodeWithTag("album-name-save").performClick()
        compose.waitUntilSettled { albumHoldsFirstPhoto("From the viewer") }
    }
}

/** Longer than a status message stays on screen. */
private const val MESSAGE_TIMEOUT_MILLIS = 6_000L
