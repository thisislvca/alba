package me.lvca.alba.app.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.AlbaViewModel
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.scrollToGalleryMedia

class PhotoTransitionTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }

    private fun viewerReady(id: String) = compose.waitUntil(UI_WAIT) {
        compose.onAllNodesWithTag("photo-viewer-$id", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun imageExpandsFromTileAndShrinksBackWithoutRemovingOutgoingViewer() {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        val id = "fixture:icloud:0020"
        kotlinx.coroutines.runBlocking { (compose.activity.application as AlbaApplication).graph.galleryRepository.keepOriginalOffline(id) }
        compose.scrollToGalleryMedia(id)
        val tile = compose.onNodeWithTag("photo-tile-$id", useUnmergedTree = true)
        // Let initial grid placement finish before measuring the transition's endpoint.
        compose.mainClock.advanceTimeBy(1200)
        val start = tile.getUnclippedBoundsInRoot()
        val viewportWidth = compose.onNodeWithTag("gallery-grid").getUnclippedBoundsInRoot().width
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("media-$id").performClick()
        val model = androidx.lifecycle.ViewModelProvider(compose.activity)[AlbaViewModel::class.java]
        // Selection is projected off the UI thread; do not spend animation frames
        // while waiting for that work to publish the viewer destination.
        compose.waitUntil(SHORT_WAIT) { model.gallery.state.value.selectedMedia?.id == id }
        // Projection, decoding and layout are asynchronous. Find an actual intermediate
        // frame instead of advancing another fixed 128 ms after the image node appears.
        val image = compose.onNodeWithTag("photo-viewer-$id", useUnmergedTree = true)
        var expanding: androidx.compose.ui.unit.DpRect? = null
        compose.waitUntil(SHORT_WAIT) {
            compose.mainClock.advanceTimeByFrame()
            if (compose.onAllNodesWithTag("photo-viewer-$id", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) false
            else image.getUnclippedBoundsInRoot().let { bounds ->
                (bounds.width > start.width + 10.dp && bounds.width < viewportWidth - 10.dp).also { if (it) expanding = bounds }
            }
        }
        compose.mainClock.advanceTimeBy(400)
        val full = image.getUnclippedBoundsInRoot()
        val intermediate = requireNotNull(expanding)
        assertTrue("Photo should grow from tile width ($start -> $intermediate -> $full)", intermediate.width > start.width + 10.dp && intermediate.width < full.width - 10.dp)
        assertTrue("Photo should move toward its final vertical position",
            kotlin.math.abs(intermediate.top.value - full.top.value) < kotlin.math.abs(start.top.value - full.top.value))

        compose.onNodeWithContentDescription("Back to gallery").performClick()
        // Projection, image decoding, and lazy-grid measurement finish asynchronously. Sample
        // actual frames instead of assuming the return element exists at a fixed wall-clock time.
        compose.waitUntil(SHORT_WAIT) {
            compose.mainClock.advanceTimeByFrame()
            val present = compose.onAllNodesWithTag("photo-tile-$id", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            present && tile.getUnclippedBoundsInRoot().width.let { it > start.width + 10.dp && it < full.width - 10.dp }
        }
        compose.onNodeWithTag("media-pager").assertExists()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("media-pager").assertDoesNotExist()
        val end = tile.getUnclippedBoundsInRoot()
        assertEquals(start.left.value, end.left.value, 1f)
        assertEquals(start.top.value, end.top.value, 1f)
        assertEquals(start.width.value, end.width.value, 1f)
        compose.mainClock.autoAdvance = true
    }

    @Test fun returningAfterPagingBringsCurrentPhotoBackIntoGrid() {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        compose.onNodeWithTag("media-fixture:icloud:0020").performClick()
        viewerReady("fixture:icloud:0020")
        repeat(7) {
            compose.onNodeWithTag("media-pager").performTouchInput {
                swipe(Offset(width * .8f, height * .5f), Offset(width * .2f, height * .5f), 300)
            }
        }
        val current = compose.onAllNodes(hasTestTagPrefix("photo-viewer-"), useUnmergedTree = true).fetchSemanticsNodes().single()
            .config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].removePrefix("photo-viewer-")
        compose.onNodeWithContentDescription("Back to gallery").performClick()
        compose.onNodeWithTag("media-$current").assertIsDisplayed()
        compose.scrollToGalleryMedia(current)
        compose.onNodeWithTag("media-$current").performClick()
        viewerReady(current)
        compose.onNodeWithTag("viewer-image-$current").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        viewerReady(current)
        compose.onNodeWithTag("viewer-image-$current").assertIsDisplayed()
    }

    @Test fun systemBackDuringOpeningReturnsToUsableGrid() {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        compose.mainClock.autoAdvance = false
        repeat(3) {
            compose.onNodeWithTag("media-fixture:icloud:0020").performClick()
            // Selection is projected off-main. Wait for the viewer to enter composition before
            // measuring an interruption of its animation (rather than Back on the library).
            compose.waitUntil(SHORT_WAIT) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isNotEmpty()
            }
            compose.mainClock.advanceTimeBy(96)
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(SHORT_WAIT) {
                compose.mainClock.advanceTimeBy(32)
                compose.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("media-pager").assertDoesNotExist()
            compose.onNodeWithTag("media-fixture:icloud:0020").assertIsDisplayed()
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun zoomedLandscapePhotoCanFillItsLetterbox() {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        // The live-photo demo is portrait; use an actual landscape photo for letterboxing.
        val id = "fixture:icloud:0001"
        compose.scrollToGalleryMedia(id)
        compose.onNodeWithTag("media-$id").performClick()
        viewerReady(id)
        val viewport = compose.onNodeWithTag("viewer-image-$id")
        val imageBounds = compose.onNodeWithTag("photo-viewer-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val viewportBounds = viewport.fetchSemanticsNode().boundsInRoot
        val x = (viewportBounds.width / 2).toInt()
        val y = (imageBounds.top - viewportBounds.top - 60).toInt()
        assertTrue(y > 0)
        val beforePixels = viewport.captureToImage().toPixelMap()
        val before = beforePixels[x, y]
        val canvas = beforePixels[2, y]
        assertTrue("Letterbox should show the viewer canvas before zoom",
            kotlin.math.abs(before.red - canvas.red) + kotlin.math.abs(before.green - canvas.green) + kotlin.math.abs(before.blue - canvas.blue) < .05f)
        viewport.performTouchInput { doubleClick() }
        val after = viewport.captureToImage().toPixelMap()[x, y]
        assertTrue("Zoomed photo must extend beyond its original fitted rectangle", kotlin.math.abs(after.red - before.red) + kotlin.math.abs(after.green - before.green) + kotlin.math.abs(after.blue - before.blue) > .2f)
    }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag begins with $prefix") {
        it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith(prefix)
    }
}
