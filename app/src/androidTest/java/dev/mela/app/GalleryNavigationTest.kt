package dev.mela.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.mela.engine.model.MediaAvailability
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GalleryNavigationTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun loaded() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("media-fixture:icloud:0020").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val dir = instrumentation.targetContext.getExternalFilesDir("redesign")!!
        dir.mkdirs()
        java.io.File(dir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun libraryLeadsWithPhotosAndDestinationsPreserveTheirOwnState() {
        loaded()
        if (compose.onAllNodesWithText("Not now").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Not now").performScrollTo().performClick()
            compose.onNodeWithTag("gallery-grid").performScrollToIndex(0)
        }
        compose.waitUntil(15_000) { runBlocking { (compose.activity.application as MelaApplication).graph.galleryRepository.observeGallery().first()
            .first { it.id == "fixture:icloud:0020" }.availability != MediaAvailability.CLOUD_ONLY } }
        val first = compose.onAllNodes(SemanticsMatcher("gallery media tile") {
            it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith("media-")
        }).fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.height > 0 }.minBy { it.top }
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("shared-albums-strip").assertIsDisplayed()
        assertTrue("The photo grid should be visible below shared albums", first.top < screen.height * .8f)
        capture("library.png")
        compose.onNodeWithTag("gallery-grid").performScrollToKey("fixture:icloud:0001")
        compose.onNodeWithTag("tab-SEARCH").performClick()
        capture("search.png")
        compose.onNodeWithText("Search filenames").performTextInput("Sea breeze")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithText("Favorites").assertIsDisplayed()
        capture("collections.png")
        compose.onNodeWithTag("tab-LIBRARY").performClick()
        compose.onNodeWithTag("media-fixture:icloud:0001").assertIsDisplayed()
        compose.onNodeWithTag("tab-SEARCH").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Sea breeze").fetchSemanticsNodes().isNotEmpty() }
        capture("search-restored.png")
        compose.onNodeWithTag("tab-SEARCH").assertIsSelected()
        compose.onNodeWithTag("search-field").assertTextContains("Sea breeze")
        compose.onNodeWithText("Sea breeze").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Sea breeze").assertIsDisplayed()
        compose.onNodeWithTag("tab-LIBRARY").performClick()
        compose.onNodeWithTag("media-fixture:icloud:0001").assertIsDisplayed().performClick()
        compose.onNodeWithText("Details").assertIsDisplayed()
        capture("viewer.png")
        compose.onNodeWithText("Details").performClick()
        capture("photo-actions.png")
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithContentDescription("Back to gallery").performClick()
        compose.onNodeWithTag("media-fixture:icloud:0001").assertIsDisplayed()
    }
    @Test fun filtersRestoreAndDoNotLeakIntoCollections() {
        loaded()
        compose.onNodeWithTag("filter-button").performClick()
        compose.onNodeWithText("Favorites").performScrollTo().performClick()
        compose.onNodeWithText("Oldest first").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Oldest first").assertIsSelected()
        capture("filters.png")
        compose.onNodeWithText("Show photos").performClick()
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-albums").assertIsDisplayed()
        compose.onNodeWithTag("tab-LIBRARY").performClick()
        compose.onNodeWithTag("filter-button").performClick()
        compose.onNodeWithText("Favorites").assertIsSelected()
        compose.onNodeWithText("Oldest first").assertIsSelected()
    }
    @Test fun wideWindowUsesRailAndPreservesDestination() {
        loaded()
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("navigation-rail").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("tab-COLLECTIONS").assertIsSelected()
            compose.onNodeWithTag("collection-albums").assertIsDisplayed()
            capture("landscape-collections.png")
            compose.onNodeWithTag("tab-LIBRARY").performClick()
            capture("landscape-library.png")
        } finally { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }
    @Test fun capturesCurrentWindowLayout() {
        loaded()
        capture("window-library.png")
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        capture("window-collections.png")
    }
}
