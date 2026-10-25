package me.lvca.alba.app.gallery.grid

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.pressBack
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.testing.waitForGalleryMedia
import me.lvca.alba.app.testing.waitUntilDisplayed
import me.lvca.alba.app.testing.withLongLibrary
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GalleryGridTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }

    @Test fun dragSelectionSelectsRangeAndCancelReturnsNavigation() {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        compose.onNodeWithTag("media-fixture:icloud:0020").performTouchInput { longClick() }
        compose.onNodeWithTag("media-fixture:icloud:0020").assertIsSelected()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.cancel_selection)).performClick()
        val tile = compose.onNodeWithTag("media-fixture:icloud:0020").fetchSemanticsNode().boundsInRoot
        // The default level groups photos by day, so the next photo is on the next row rather than beside this one.
        val next = compose.onNodeWithTag("media-fixture:icloud:0019").fetchSemanticsNode().boundsInRoot
        val grid = compose.onNodeWithTag("gallery-grid").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("gallery-grid").performTouchInput {
            val start = tile.center - grid.topLeft
            down(start); advanceEventTime(650)
            moveTo(Offset(start.x, next.center.y - grid.top), 300); up()
        }
        val count = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).fetchSemanticsNodes().size
        assertTrue("Dragging should select a range, not just the first tile", count >= 2)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.cancel_selection)).performClick()
        compose.onNodeWithTag("tab-LIBRARY").assertIsDisplayed()
    }

    @Test fun pinchChangesGridSizeAndDateScrollerJumps() = compose.withLongLibrary {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        val before = compose.onNodeWithTag("media-fixture:icloud:0020").fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithTag("gallery-grid").performTouchInput {
            pinch(Offset(width * .4f, height * .35f), Offset(width * .2f, height * .35f), Offset(width * .6f, height * .35f), Offset(width * .8f, height * .35f), 450)
        }
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        val after = compose.onNodeWithTag("media-fixture:icloud:0020").fetchSemanticsNode().boundsInRoot.width
        assertTrue("Pinch out should enlarge tiles", after > before)
        // The 100+ item library gets the date scrubber, and jumping with it moves the grid.
        val tile = compose.onNodeWithTag("media-fixture:icloud:0020").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("gallery-fast-scroll").performSemanticsAction(SemanticsActions.SetProgress) { it(.8f) }
        compose.waitForIdle()
        val moved = compose.onAllNodesWithTag("media-fixture:icloud:0020").fetchSemanticsNodes().none { it.boundsInRoot == tile }
        assertTrue("The date scrubber should move the grid", moved)
    }

    @Test
    fun androidBackClearsSelectionAndDetailKeepsGalleryPosition() {
        compose.waitForGalleryMedia()
        val media = "media-fixture:icloud:0019"
        compose.scrollToGalleryMedia("fixture:icloud:0019")
        compose.onNodeWithTag(media).performTouchInput { longClick() }
        compose.onNodeWithTag(media).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.pressBack()
        compose.onNodeWithTag(media).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        compose.onNodeWithTag(media).performClick()
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilDisplayed(hasTestTag(media))
    }
}
