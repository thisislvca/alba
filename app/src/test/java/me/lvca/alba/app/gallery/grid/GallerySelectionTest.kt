package me.lvca.alba.app.gallery.grid

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.R
import me.lvca.alba.app.screenshots.ScreenshotFixtures
import me.lvca.alba.app.testing.GalleryStateActions
import me.lvca.alba.app.testing.pressBack
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.testing.setAlbaContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Selecting in the grid: long press, drag across a range, cancel and Back. */
@RunWith(AndroidJUnit4::class)
class GallerySelectionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val actions = GalleryStateActions(ScreenshotFixtures.gallery())

    // The fixtures have three photos a day, and the default level groups photos by day, so photo-3 starts the next row.
    private val first = "photo-0"
    private val nextRow = "photo-3"

    @Test fun dragSelectionSelectsARangeAndCancelReturnsNavigation() {
        compose.setAlbaContent(actions.state, ScreenshotFixtures.signedIn, actions)
        compose.scrollToGalleryMedia(first)
        compose.onNodeWithTag("media-$first").performTouchInput { longClick() }
        compose.onNodeWithTag("media-$first").assertIsSelected()
        compose.onNodeWithContentDescription(context.getString(R.string.cancel_selection)).performClick()
        compose.onNodeWithTag("media-$first").assertIsNotSelected()
        val tile = compose.onNodeWithTag("media-$first").fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithTag("media-$nextRow").fetchSemanticsNode().boundsInRoot
        val grid = compose.onNodeWithTag("gallery-grid").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("gallery-grid").performTouchInput {
            val start = tile.center - grid.topLeft
            down(start); advanceEventTime(650)
            moveTo(Offset(start.x, next.center.y - grid.top), 300); up()
        }
        val selected = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).fetchSemanticsNodes().size
        assertTrue("Dragging should select a range, not just the first tile ($selected)", selected >= 2)
        compose.onNodeWithContentDescription(context.getString(R.string.cancel_selection)).performClick()
        compose.onNodeWithTag("tab-LIBRARY").assertIsDisplayed()
    }

    @Test fun backClearsTheSelectionAndAPhotoOpenedFromTheGridReturnsToIt() {
        compose.setAlbaContent(actions.state, ScreenshotFixtures.signedIn, actions)
        compose.scrollToGalleryMedia(nextRow)
        compose.onNodeWithTag("media-$nextRow").performTouchInput { longClick() }
        compose.onNodeWithTag("media-$nextRow").assertIsSelected()
        compose.pressBack()
        compose.onNodeWithTag("media-$nextRow").assertIsNotSelected()
        assertEquals(emptySet<String>(), actions.state.value.selection)
        compose.onNodeWithTag("media-$nextRow").performClick()
        compose.waitUntil { compose.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isNotEmpty() }
        compose.pressBack()
        compose.waitUntil { compose.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("media-$nextRow").assertIsDisplayed()
    }
}
