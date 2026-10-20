package me.lvca.alba.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import me.lvca.alba.engine.model.MediaAvailability
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.LONG_WAIT
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.testing.waitForGalleryMedia
import me.lvca.alba.app.testing.waitUntilDisplayed

class GalleryNavigationTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }
    @Test fun libraryLeadsWithPhotosAndDestinationsPreserveTheirOwnState() {
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
        compose.onNodeWithText("Search filenames").performTextInput("Sea breeze")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
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
        compose.waitUntilDisplayed(hasTestTag("media-fixture:icloud:0001")).performClick()
        compose.waitUntilDisplayed(hasTestTag("viewer-more"))
        compose.openViewerDetails()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithContentDescription("Back to gallery").performClick()
        compose.waitUntilDisplayed(hasTestTag("media-fixture:icloud:0001"))
    }
    @Test fun filtersRestoreAndDoNotLeakIntoCollections() {
        compose.waitForGalleryMedia()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.filter_and_sort)).performClick()
        compose.onNodeWithText("Favorites").performScrollTo().performClick()
        compose.onNodeWithText("Oldest first").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Oldest first").assertIsSelected()
        compose.onNodeWithText("Show photos").performClick()
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-albums").assertIsDisplayed()
        compose.onNodeWithTag("tab-LIBRARY").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.filter_and_sort)).performClick()
        compose.onNodeWithText("Favorites").assertIsSelected()
        compose.onNodeWithText("Oldest first").assertIsSelected()
    }
    @Test fun wideWindowUsesRailAndPreservesDestination() {
        compose.waitForGalleryMedia()
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        try {
            compose.waitUntil(LONG_WAIT) { compose.onAllNodesWithTag("navigation-rail").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("tab-COLLECTIONS").assertIsSelected()
            compose.onNodeWithTag("collection-albums").assertIsDisplayed()
            compose.onNodeWithTag("tab-LIBRARY").performClick()
        } finally { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }
}
