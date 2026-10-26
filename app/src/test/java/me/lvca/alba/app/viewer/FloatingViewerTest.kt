package me.lvca.alba.app.viewer

import android.content.res.Configuration
import androidx.activity.BackEventCompat
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.AlbaAppRule
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.scrollToGalleryMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/** The viewer's gestures: details that resize the photo, back and drag to dismiss, predictive back, and zoom. */
@RunWith(AndroidJUnit4::class)
class FloatingViewerTest {
    @get:Rule val app = AlbaAppRule()
    private val compose get() = app.compose
    private val id = "fixture:icloud:0016"

    /** Opens the photo in the viewer, once the viewer has decoded it on a background thread. */
    private fun open() {
        compose.scrollToGalleryMedia(id)
        compose.onNodeWithTag("media-$id").performClick()
        compose.onNodeWithTag("media-pager").assertIsDisplayed()
        compose.waitUntil(SHORT_WAIT) {
            compose.onAllNodes(hasTestTag("photo-viewer-$id"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun photoBounds() = compose.onNodeWithTag("photo-viewer-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun dispatchBack(block: androidx.activity.OnBackPressedDispatcher.() -> Unit) =
        compose.runOnIdle { app.activity.onBackPressedDispatcher.block() }

    @Test fun swipeUpResizesPhotoAndDetailsRestoreWithoutCoveringIt() {
        open()
        val original = bounds("viewer-photo-viewport")
        compose.onNodeWithTag("media-pager").performTouchInput {
            swipe(Offset(width * .5f, height * .72f), Offset(width * .5f, height * .22f), 400)
        }
        compose.onNodeWithTag("photo-details-panel").assertIsDisplayed()
        val photo = bounds("viewer-photo-viewport")
        assertTrue("Details must occupy their own space below the photo", photo.bottom <= bounds("photo-details-panel").top + 1f)
        assertTrue("Photo stays visible above the details", photo.height > original.height * .25f && photo.height < original.height * .6f)
        compose.onNodeWithTag("details-back").assertIsDisplayed()
        app.recreate()
        compose.onNodeWithTag("photo-details-panel").assertIsDisplayed()
        assertTrue(bounds("viewer-photo-viewport").bottom <= bounds("photo-details-panel").top + 1f)
        // Turning the phone keeps the details below a visible photo.
        RuntimeEnvironment.setQualifiers("+land")
        app.recreate()
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, app.activity.resources.configuration.orientation)
        val landscapePhoto = bounds("viewer-photo-viewport")
        assertTrue(landscapePhoto.height > 0 && landscapePhoto.bottom <= bounds("photo-details-panel").top + 1f)
        compose.onNodeWithTag("details-back").assertIsDisplayed()
        RuntimeEnvironment.setQualifiers("+port")
        app.recreate()
        dispatchBack { onBackPressed() }
        compose.onNodeWithTag("photo-details-panel").assertDoesNotExist()
        compose.onNodeWithTag("media-pager").assertIsDisplayed()
    }

    @Test fun detailsCollapseByBackOrHandleThenViewerBackOrDragReturnsToGallery() {
        open()
        compose.openViewerDetails()
        compose.onNodeWithTag("details-back").performClick()
        compose.onNodeWithTag("photo-details-panel").assertDoesNotExist()
        compose.onNodeWithTag("media-pager").assertIsDisplayed()
        compose.onNodeWithTag("floating-viewer-back").performClick()
        compose.onNodeWithTag("media-pager").assertDoesNotExist()
        compose.onNodeWithTag("media-$id").performClick()
        compose.onNodeWithTag("photo-details-panel").assertDoesNotExist()
        // Dragging the handle down also collapses the details, then dragging the photo down returns to the grid.
        compose.openViewerDetails()
        compose.onNodeWithTag("details-drag-handle").performTouchInput { swipe(center, Offset(center.x, center.y + 450f), 400) }
        compose.onNodeWithTag("photo-details-panel").assertDoesNotExist()
        compose.onNodeWithTag("media-pager").assertIsDisplayed()
        compose.onNodeWithTag("media-pager").performTouchInput {
            swipe(Offset(width * .5f, height * .35f), Offset(width * .5f, height * .75f), 400)
        }
        compose.onNodeWithTag("media-pager").assertDoesNotExist()
        compose.onNodeWithTag("media-$id").assertIsDisplayed()
    }

    @Test fun predictiveBackProgressCancelsAndCompletes() {
        open()
        val initial = photoBounds()
        fun progress(value: Float, y: Float = 100f) = BackEventCompat(0f, y, value, BackEventCompat.EDGE_LEFT)
        dispatchBack { dispatchOnBackStarted(progress(0f)) }
        // The started event does not reach the handler's flow; the first progress event anchors the touch.
        dispatchBack { dispatchOnBackProgressed(progress(.05f)) }
        dispatchBack { dispatchOnBackProgressed(progress(.6f, y = 180f)) }
        compose.waitForIdle()
        val moved = photoBounds()
        // GP: scale 1 - 0.35p, a shift away from the swipe edge, and the touch's vertical movement.
        assertEquals("Back progress shrinks the photo", initial.width * (1f - .35f * .6f), moved.width, initial.width * .02f)
        assertTrue("A left-edge swipe moves the photo right", moved.center.x > initial.center.x + 20f)
        assertTrue("The photo follows the touch down", moved.center.y > initial.center.y + 20f)
        dispatchBack { dispatchOnBackCancelled() }
        compose.waitForIdle()
        val restored = photoBounds()
        assertEquals(initial.top, restored.top, 1f)
        assertEquals(initial.width, restored.width, 1f)
        dispatchBack { dispatchOnBackStarted(progress(0f)) }
        dispatchBack { dispatchOnBackProgressed(progress(.8f)) }
        dispatchBack { onBackPressed() }
        compose.onNodeWithTag("media-pager").assertDoesNotExist()
        compose.onNodeWithTag("media-$id").assertIsDisplayed()
    }

    @Test fun zoomedPhotoPansWithoutOpeningDetailsAndShortDragCancels() {
        open()
        val zoomed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, app.string(R.string.photo_zoomed))
        val image = compose.onNodeWithTag("viewer-image-$id")
        image.performTouchInput { doubleClick() }
        image.assert(zoomed)
        image.performTouchInput { swipeUp(durationMillis = 400) }
        compose.onNodeWithTag("photo-details-panel").assertDoesNotExist()
        compose.onNodeWithTag("media-pager").assertIsDisplayed()
        image.performTouchInput { doubleClick() }
        image.performTouchInput {
            pinch(Offset(width * .4f, height * .5f), Offset(width * .25f, height * .4f),
                Offset(width * .6f, height * .5f), Offset(width * .75f, height * .6f), 400)
        }
        image.assert(zoomed)
        compose.onNodeWithTag("photo-details-panel").assertDoesNotExist()
        image.performTouchInput { doubleClick() }
        // GP dismisses after ~30dp of travel; 20dp (less the touch slop) settles back.
        compose.onNodeWithTag("media-pager").performTouchInput { swipe(center, Offset(center.x, center.y + 20.dp.toPx()), 700) }
        compose.onNodeWithTag("media-pager").assertIsDisplayed()
        compose.onNodeWithTag("photo-details-panel").assertDoesNotExist()
    }

    @Test fun pinchingInAtFitClosesTheViewerAndASlightPinchReturns() {
        open()
        val photo = compose.onNodeWithTag("viewer-image-$id")
        val fit = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, app.string(R.string.photo_fit))
        // A slight pinch (about 0.9×) springs back to fit and keeps the viewer open.
        photo.performTouchInput {
            pinch(center - Offset(width * .3f, 0f), center - Offset(width * .27f, 0f),
                center + Offset(width * .3f, 0f), center + Offset(width * .27f, 0f), 300)
        }
        compose.onNodeWithTag("media-pager").assertExists()
        photo.assert(fit)
        // Pinching well in closes back to the grid, like a swipe down.
        photo.performTouchInput {
            pinch(center - Offset(width * .35f, 0f), center - Offset(width * .08f, 0f),
                center + Offset(width * .35f, 0f), center + Offset(width * .08f, 0f), 300)
        }
        compose.onNodeWithTag("media-pager").assertDoesNotExist()
        compose.onNodeWithTag("media-$id").assertExists()
    }

    @Test fun swipeDownFollowsTheFingerInBothAxesAndShortTravelSettlesBack() {
        open()
        val initial = photoBounds()
        val pager = compose.onNodeWithTag("media-pager")
        pager.performTouchInput {
            down(center)
            moveBy(Offset(0f, viewConfiguration.touchSlop + 1f))
            moveBy(Offset(12.dp.toPx(), 6.dp.toPx()))
        }
        compose.waitForIdle()
        val dragged = photoBounds()
        assertTrue("The photo follows the finger sideways", dragged.center.x > initial.center.x + 8f)
        assertTrue("The photo shrinks as it is pulled", dragged.width < initial.width * .98f)
        pager.performTouchInput { up() }
        compose.waitForIdle()
        pager.assertIsDisplayed()
        val settled = photoBounds()
        assertEquals(initial.left, settled.left, 1f)
        assertEquals(initial.width, settled.width, 1f)
        pager.performTouchInput {
            down(center)
            moveBy(Offset(0f, viewConfiguration.touchSlop + 1f))
            moveBy(Offset(-20.dp.toPx(), 40.dp.toPx()))
            up()
        }
        compose.onNodeWithTag("media-pager").assertDoesNotExist()
        compose.onNodeWithTag("media-$id").assertIsDisplayed()
    }
}
