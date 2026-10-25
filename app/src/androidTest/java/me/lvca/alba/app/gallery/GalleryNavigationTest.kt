package me.lvca.alba.app.gallery

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
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.backGesture
import me.lvca.alba.app.testing.hideKeyboard
import me.lvca.alba.app.testing.openGalleryMedia
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.pressBack
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.testing.showFloatingDate
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
    private fun hasNodeWithText(text: String): Boolean =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

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
    @Test fun tabTapAnswersAtOnceAndSwitchesOnceTheScreenHasFaded() {
        compose.waitForGalleryMedia()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("tab-COLLECTIONS").performClick()
            compose.mainClock.advanceTimeByFrame()
            // The navigation answers in the first frame; Photos fades out before Collections composes.
            compose.onNodeWithTag("tab-COLLECTIONS").assertIsSelected()
            compose.onAllNodesWithTag("collection-albums").assertCountEquals(0)
            compose.mainClock.advanceTimeBy(500)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.onNodeWithTag("collection-albums").assertIsDisplayed()
        compose.onNodeWithTag("tab-LIBRARY").performClick()
        compose.waitUntilDisplayed(hasTestTag("gallery-grid"))
        compose.onNodeWithTag("tab-LIBRARY").assertIsSelected()
    }

    @Test fun backGestureCancelKeepsTheScreenAndCommitPopsIt() {
        compose.waitForGalleryMedia()
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-albums").performClick()
        compose.onNodeWithText("Journeys").assertIsDisplayed()
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

    @Test fun floatingDestinationsHaveIconsAndSeparateSearch() {
        compose.waitForGalleryMedia()
        val capsule = compose.onNodeWithTag("gallery-destination-capsule").fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithTag("tab-SEARCH").fetchSemanticsNode().boundsInRoot
        assertTrue("Search must be separate from the two-destination capsule", search.left > capsule.right)
        compose.onNodeWithTag("tab-LIBRARY").assertIsSelected()
        compose.onNodeWithTag("tab-COLLECTIONS").performClick().assertIsSelected()
        compose.onNodeWithTag("tab-SEARCH").performClick().assertIsSelected()
        compose.onNodeWithTag("search-field").assertIsDisplayed()
    }

    @Test
    fun searchFavoritesAlbumsAndSelectionAreUsable() {
        compose.waitForGalleryMedia()
        compose.onNodeWithTag("tab-SEARCH").performClick()
        compose.onNodeWithText("Search filenames").performTextInput("Sea breeze")
        // libraryLeadsWithPhotosAndDestinationsPreserveTheirOwnState checks that the search survives recreation.
        // The navigation steps aside while the keyboard is open, so close it as a recreation used to.
        compose.hideKeyboard()
        compose.waitUntilDisplayed(hasTestTag("tab-COLLECTIONS"))
        compose.scrollToGalleryMedia("fixture:icloud:0019")
        compose.onNodeWithTag("media-fixture:icloud:0019").performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel selection").performClick()
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithText("1 selected").assertDoesNotExist()
        compose.onNodeWithText("Favorites").performClick()
        compose.scrollToGalleryMedia("fixture:icloud:0019")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.back_action)).performClick()
        compose.onNodeWithTag("collection-albums").performClick()
        compose.onNodeWithText("Journeys").performClick()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(timeoutMillis = UI_WAIT) { hasNodeWithText("By the sea") }
        compose.onNodeWithText("By the sea").performClick()
        compose.onNodeWithText("By the sea").assertIsDisplayed()
    }

    @Test fun dateAndDensityRestoreAndMonthJumpReachesPhotos() {
        compose.waitForGalleryMedia()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.filter_and_sort)).performClick()
        compose.onNodeWithText("Added", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Large").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Added").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Large").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Show photos").performClick()
        compose.showFloatingDate().performClick()
        // Demo spans months; selecting the earliest jumps to that month's grid header.
        val repository = (compose.activity.application as AlbaApplication).graph.galleryRepository
        val oldest = runBlocking { repository.observeGallery().first()
            .filterNot { it.id.startsWith("shared:") }
            .minBy { it.addedAtEpochMillis ?: it.capturedAtEpochMillis } }
        val label = java.time.Instant.ofEpochMilli(oldest.addedAtEpochMillis ?: oldest.capturedAtEpochMillis).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"))
        compose.onNodeWithTag("date-months").performScrollToNode(hasText(label))
        compose.onAllNodesWithText(label).onLast().performClick()
        compose.onNodeWithTag("media-${oldest.id}").assertExists()
    }
}
