package dev.mela.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FloatingViewerTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val id = "fixture:icloud:0016"
    private fun loaded() = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag("photo-tile-fixture:icloud:0020", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    private fun open() {
        loaded()
        compose.onNodeWithTag("gallery-grid").performScrollToKey(id)
        compose.onNodeWithTag("media-$id").performClick()
        compose.onNodeWithTag("media-pager").assertIsDisplayed()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val dir = instrumentation.targetContext.getExternalFilesDir("floating-gallery")!!.apply { mkdirs() }
        java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun floatingDestinationsHaveIconsAndSeparateSearch() {
        loaded()
        val capsule = compose.onNodeWithTag("gallery-destination-capsule").fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithTag("tab-SEARCH").fetchSemanticsNode().boundsInRoot
        assertTrue("Search must be separate from the two-destination capsule", search.left > capsule.right)
        compose.onNodeWithTag("tab-LIBRARY").assertIsSelected()
        capture("01-floating-library")
        compose.onNodeWithTag("tab-COLLECTIONS").performClick().assertIsSelected()
        capture("02-floating-collections")
        compose.onNodeWithTag("tab-SEARCH").performClick().assertIsSelected()
        compose.onNodeWithTag("search-field").assertIsDisplayed()
        capture("03-search")
    }
    @Test fun swipeUpResizesPhotoAndDetailsRestoreWithoutCoveringIt() {
        open()
        val original = compose.onNodeWithTag("viewer-photo-viewport").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("media-pager").performTouchInput {
            swipe(Offset(width * .5f, height * .72f), Offset(width * .5f, height * .22f), 400)
        }
        compose.onNodeWithTag("photo-details-panel").assertIsDisplayed()
        val photo = compose.onNodeWithTag("viewer-photo-viewport").fetchSemanticsNode().boundsInRoot
        val details = compose.onNodeWithTag("photo-details-panel").fetchSemanticsNode().boundsInRoot
        assertTrue("Details must occupy their own space below the photo", photo.bottom <= details.top + 1f)
        assertTrue("Photo stays visible above the details", photo.height > original.height * .25f && photo.height < original.height * .6f)
        compose.onNodeWithTag("floating-viewer-back").assertIsDisplayed()
        capture("04-details-push-photo")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("photo-details-panel").assertIsDisplayed()
        val restoredPhoto = compose.onNodeWithTag("viewer-photo-viewport").fetchSemanticsNode().boundsInRoot
        val restoredDetails = compose.onNodeWithTag("photo-details-panel").fetchSemanticsNode().boundsInRoot
        assertTrue(restoredPhoto.bottom <= restoredDetails.top + 1f)
        compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        try {
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            val landscapePhoto = compose.onNodeWithTag("viewer-photo-viewport").fetchSemanticsNode().boundsInRoot
            val landscapeDetails = compose.onNodeWithTag("photo-details-panel").fetchSemanticsNode().boundsInRoot
            assertTrue(landscapePhoto.height > 0 && landscapePhoto.bottom <= landscapeDetails.top + 1f)
            compose.onNodeWithTag("floating-viewer-back").assertIsDisplayed()
            // Compose can be idle while the system window rotation is still animating.
            android.os.SystemClock.sleep(500)
            capture("07-landscape-details")
        } finally { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("photo-details-panel").assertDoesNotExist()
        compose.onNodeWithTag("media-pager").assertIsDisplayed()
    }
    @Test fun floatingBackLeavesExpandedDetailsAndReopeningStartsWithFullPhoto() {
        open()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.photo_details_and_actions)).performClick()
        compose.onNodeWithTag("photo-details-panel").assertIsDisplayed()
        compose.onNodeWithTag("floating-viewer-back").performClick()
        compose.onNodeWithTag("media-pager").assertDoesNotExist()
        compose.onNodeWithTag("media-$id").performClick()
        compose.onNodeWithTag("photo-details-panel").assertDoesNotExist()
        compose.onNodeWithTag("media-pager").performTouchInput {
            swipe(Offset(width * .5f, height * .35f), Offset(width * .5f, height * .75f), 400)
        }
        compose.onNodeWithTag("media-pager").assertDoesNotExist()
    }
    @Test fun handleCollapsesDetailsThenDownwardPhotoDragReturnsToGrid() {
        open()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.photo_details_and_actions)).performClick()
        compose.onNodeWithTag("details-drag-handle").performTouchInput {
            swipe(center, Offset(center.x, center.y + 450f), 400)
        }
        compose.onNodeWithTag("photo-details-panel").assertDoesNotExist()
        compose.onNodeWithTag("media-pager").assertIsDisplayed()
        compose.onNodeWithTag("media-pager").performTouchInput {
            swipe(Offset(width * .5f, height * .35f), Offset(width * .5f, height * .75f), 400)
        }
        compose.onNodeWithTag("media-pager").assertDoesNotExist()
        compose.onNodeWithTag("media-$id").assertIsDisplayed()
        capture("05-drag-returned")
    }
    @Test fun predictiveBackProgressCancelsAndCompletes() {
        open()
        val initial = compose.onNodeWithTag("media-pager").fetchSemanticsNode().boundsInRoot
        fun progress(value: Float) = androidx.activity.BackEventCompat(0f, 100f, value, androidx.activity.BackEventCompat.EDGE_LEFT)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackStarted(progress(0f)) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackProgressed(progress(.6f)) }
        compose.waitForIdle()
        val moved = compose.onNodeWithTag("media-pager").fetchSemanticsNode().boundsInRoot
        assertTrue("Back progress moves the photo with the gesture", moved.top > initial.top + 80f)
        capture("08-predictive-back-progress")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertEquals(initial.top, compose.onNodeWithTag("media-pager").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackStarted(progress(0f)) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackProgressed(progress(.8f)) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("media-pager").assertDoesNotExist()
        compose.onNodeWithTag("media-$id").assertIsDisplayed()
    }

    @Test fun zoomedPhotoPansWithoutOpeningDetailsAndShortDragCancels() {
        open()
        val image = compose.onNodeWithTag("viewer-image-$id")
        image.performTouchInput { doubleClick() }
        image.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, compose.activity.getString(R.string.photo_zoomed)))
        image.performTouchInput { swipeUp(durationMillis = 400) }
        compose.onNodeWithTag("photo-details-panel").assertDoesNotExist()
        compose.onNodeWithTag("media-pager").assertIsDisplayed()
        image.performTouchInput { doubleClick() }
        image.performTouchInput {
            pinch(Offset(width * .4f, height * .5f), Offset(width * .25f, height * .4f),
                Offset(width * .6f, height * .5f), Offset(width * .75f, height * .6f), 400)
        }
        image.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, compose.activity.getString(R.string.photo_zoomed)))
        compose.onNodeWithTag("photo-details-panel").assertDoesNotExist()
        image.performTouchInput { doubleClick() }
        compose.onNodeWithTag("media-pager").performTouchInput {
            swipe(center, Offset(center.x, center.y + height * .045f), 700)
        }
        compose.onNodeWithTag("media-pager").assertIsDisplayed()
        compose.onNodeWithTag("photo-details-panel").assertDoesNotExist()
        capture("06-cancelled-drag")
    }
}
