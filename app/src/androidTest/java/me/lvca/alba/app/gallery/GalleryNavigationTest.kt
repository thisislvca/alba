package me.lvca.alba.app.gallery

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.Smoke
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.backGesture
import me.lvca.alba.app.testing.hideKeyboard
import me.lvca.alba.app.testing.openGalleryMedia
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.pressBack
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.testing.waitForGalleryMedia
import me.lvca.alba.app.testing.waitUntilDisplayed
import me.lvca.alba.engine.model.MediaAvailability
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GalleryNavigationTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }

    @Smoke @Test fun libraryLeadsWithPhotosAndDestinationsPreserveTheirOwnState() {
        compose.waitForGalleryMedia()
        if (compose.onAllNodesWithText("Not now").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Not now").performScrollTo().performClick()
            compose.onNodeWithTag("gallery-grid").performScrollToIndex(0)
        }
        compose.waitUntil(UI_WAIT) { runBlocking { (compose.activity.application as AlbaApplication).graph.galleryRepository.observeGallery().first()
            .first { it.id == "fixture:icloud:0020" }.availability != MediaAvailability.CLOUD_ONLY } }
        val first = compose.onAllNodes(SemanticsMatcher("gallery media tile") {
            it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith("media-")
        }).fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.height > 0 }.minBy { it.top }
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("shared-albums-strip").assertIsDisplayed()
        assertTrue("The photo grid should be visible below shared albums", first.top < screen.height * .8f)
        compose.scrollToGalleryMedia("fixture:icloud:0001")
        compose.onNodeWithTag("tab-SEARCH").performClick()
        compose.onNodeWithTag("search-field").performTextInput("Sea breeze")
        compose.hideKeyboard()
        // The navigation steps aside while the keyboard is open and returns once it has closed.
        compose.waitUntilDisplayed(hasTestTag("tab-COLLECTIONS"))
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithText("Favorites").assertIsDisplayed()
        compose.onNodeWithTag("tab-LIBRARY").performClick()
        compose.waitUntilDisplayed(hasTestTag("media-fixture:icloud:0001"))
        compose.onNodeWithTag("tab-SEARCH").performClick()
        compose.waitUntil(SHORT_WAIT) { compose.onAllNodesWithText("Sea breeze").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab-SEARCH").assertIsSelected()
        compose.onNodeWithTag("search-field").assertTextContains("Sea breeze")
        compose.onNodeWithText("Sea breeze").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitUntilDisplayed(hasText("Sea breeze"))
        compose.onNodeWithTag("tab-LIBRARY").performClick()
        // The restored scroll position can leave the photo under the floating date button, so move it clear first.
        compose.openGalleryMedia("fixture:icloud:0001")
        compose.waitUntilDisplayed(hasTestTag("viewer-more"))
        compose.openViewerDetails()
        compose.pressBack()
        compose.onNodeWithContentDescription("Back to gallery").performClick()
        compose.waitUntilDisplayed(hasTestTag("media-fixture:icloud:0001"))
    }
    @Smoke @Test fun backGestureCancelKeepsTheScreenAndCommitPopsIt() {
        compose.waitForGalleryMedia()
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-albums").performClick()
        // The album list fills once the catalog has synced its collections; until then the page reads "0 albums".
        compose.waitUntilDisplayed(hasText("Journeys"))
        compose.backGesture(commit = false)
        compose.onNodeWithText("Journeys").assertIsDisplayed()
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
