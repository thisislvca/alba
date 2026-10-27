package me.lvca.alba.app.gallery

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.testing.AlbaAppRule
import me.lvca.alba.app.testing.backGesture
import me.lvca.alba.app.testing.hideKeyboard
import me.lvca.alba.app.testing.openGalleryMedia
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.pressBack
import me.lvca.alba.app.testing.scrollToGalleryMedia
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Moving between the app's destinations: each keeps its own state, across recreation, and back steps out one level. */
@RunWith(AndroidJUnit4::class)
class GalleryNavigationTest {
    @get:Rule val app = AlbaAppRule()
    private val compose get() = app.compose

    @Test fun libraryLeadsWithPhotosAndDestinationsPreserveTheirOwnState() {
        val tiles = compose.onAllNodes(SemanticsMatcher("gallery media tile") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("media-") == true
        }).fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.height > 0 }
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("shared-albums-strip").assertIsDisplayed()
        assertTrue("The photo grid should be visible below shared albums", tiles.minOf { it.top } < screen.height * .8f)

        compose.scrollToGalleryMedia("fixture:icloud:0001")
        compose.onNodeWithTag("tab-SEARCH").performClick()
        compose.onNodeWithTag("search-field").performTextInput("Sea breeze")
        compose.hideKeyboard()
        // The navigation steps aside while the keyboard is open and returns once it has closed.
        compose.onNodeWithTag("tab-COLLECTIONS").assertIsDisplayed().performClick()
        compose.onNodeWithTag("collection-favorites").assertIsDisplayed()
        // The library keeps its scroll position, Search its query.
        compose.onNodeWithTag("tab-LIBRARY").performClick()
        compose.onNodeWithTag("media-fixture:icloud:0001").assertIsDisplayed()
        compose.onNodeWithTag("tab-SEARCH").performClick()
        compose.onNodeWithTag("tab-SEARCH").assertIsSelected()
        compose.onNodeWithTag("search-field").assertTextContains("Sea breeze")

        app.recreate()
        compose.onNodeWithTag("tab-SEARCH").assertIsSelected()
        compose.onNodeWithTag("search-field").assertTextContains("Sea breeze")
        compose.onNodeWithTag("tab-LIBRARY").performClick()
        // The restored scroll position can leave the photo under the floating date button, so move it clear first.
        compose.openGalleryMedia("fixture:icloud:0001")
        compose.onNodeWithTag("viewer-more").assertIsDisplayed()
        compose.openViewerDetails()
        // Back closes the details first, then the viewer's own back returns to the grid.
        compose.pressBack()
        compose.onNodeWithTag("photo-details-panel").assertDoesNotExist()
        compose.onNodeWithTag("floating-viewer-back").performClick()
        compose.onNodeWithTag("media-pager").assertDoesNotExist()
        compose.onNodeWithTag("media-fixture:icloud:0001").assertIsDisplayed()
    }

    @Test fun backGestureCancelKeepsTheScreenAndCommitPopsIt() {
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-albums").performClick()
        val journeys = hasText("Journeys")
        compose.onNode(journeys).assertIsDisplayed()
        compose.backGesture(commit = false)
        compose.onNode(journeys).assertIsDisplayed()
        compose.onNodeWithTag("collection-albums").assertDoesNotExist()
        compose.backGesture(commit = true)
        compose.onNodeWithTag("collection-albums").assertIsDisplayed()
        compose.onNodeWithTag("account-button").performClick()
        compose.onNodeWithTag("alba-page-scroll").assertIsDisplayed()
        compose.backGesture(commit = false)
        compose.onNodeWithTag("alba-page-scroll").assertIsDisplayed()
        compose.backGesture(commit = true)
        compose.onNodeWithTag("alba-page-scroll").assertDoesNotExist()
        compose.onNodeWithTag("collection-albums").assertIsDisplayed()
    }
}
