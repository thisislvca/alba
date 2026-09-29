package dev.mela.app

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhotoTransitionTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun loaded() {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
    }

    private fun viewerReady(id: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag("photo-viewer-$id", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = instrumentation.targetContext.getExternalFilesDir("photo-transitions")!!.apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun imageExpandsFromTileAndShrinksBackWithoutRemovingOutgoingViewer() {
        loaded()
        val id = "fixture:icloud:0020"
        compose.onNodeWithTag("gallery-grid").performScrollToKey(id)
        val tile = compose.onNodeWithTag("photo-tile-$id", useUnmergedTree = true)
        val start = tile.getUnclippedBoundsInRoot()
        val viewportWidth = compose.onNodeWithTag("gallery-grid").getUnclippedBoundsInRoot().width
        capture("01-grid")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("media-$id").performClick()
        // Projection, decoding and layout are asynchronous. Find an actual intermediate
        // frame instead of advancing another fixed 128 ms after the image node appears.
        val image = compose.onNodeWithTag("photo-viewer-$id", useUnmergedTree = true)
        var expanding: androidx.compose.ui.unit.DpRect? = null
        compose.waitUntil(5_000) {
            compose.mainClock.advanceTimeByFrame()
            if (compose.onAllNodesWithTag("photo-viewer-$id", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) false
            else image.getUnclippedBoundsInRoot().let { bounds ->
                (bounds.width > start.width + 10.dp && bounds.width < viewportWidth - 10.dp).also { if (it) expanding = bounds }
            }
        }
        capture("02-opening")
        compose.mainClock.advanceTimeBy(400)
        val full = image.getUnclippedBoundsInRoot()
        capture("03-viewer")
        val intermediate = requireNotNull(expanding)
        assertTrue("Photo should grow from tile width ($start -> $intermediate -> $full)", intermediate.width > start.width + 10.dp && intermediate.width < full.width - 10.dp)
        assertTrue("Photo should move toward its final vertical position",
            kotlin.math.abs(intermediate.top.value - full.top.value) < kotlin.math.abs(start.top.value - full.top.value))

        compose.onNodeWithContentDescription("Back to gallery").performClick()
        // Projection, image decoding, and lazy-grid measurement finish asynchronously. Sample
        // actual frames instead of assuming the return element exists at a fixed wall-clock time.
        compose.waitUntil(5_000) {
            compose.mainClock.advanceTimeByFrame()
            val present = compose.onAllNodesWithTag("photo-tile-$id", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            present && tile.getUnclippedBoundsInRoot().width.let { it > start.width + 10.dp && it < full.width - 10.dp }
        }
        compose.onNodeWithTag("media-pager").assertExists()
        capture("04-closing")
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("media-pager").assertDoesNotExist()
        val end = tile.getUnclippedBoundsInRoot()
        assertEquals(start.left.value, end.left.value, 1f)
        assertEquals(start.top.value, end.top.value, 1f)
        assertEquals(start.width.value, end.width.value, 1f)
        capture("05-returned")
        compose.mainClock.autoAdvance = true
    }

    @Test fun returningAfterPagingBringsCurrentPhotoBackIntoGrid() {
        loaded()
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
        compose.onNodeWithTag("media-$current").performClick()
        viewerReady(current)
        compose.onNodeWithTag("viewer-image-$current").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        viewerReady(current)
        compose.onNodeWithTag("viewer-image-$current").assertIsDisplayed()
    }

    @Test fun systemBackDuringOpeningReturnsToUsableGrid() {
        loaded()
        compose.mainClock.autoAdvance = false
        repeat(3) {
            compose.onNodeWithTag("media-fixture:icloud:0020").performClick()
            // Selection is projected off-main. Wait for the viewer to enter composition before
            // measuring an interruption of its animation (rather than Back on the library).
            compose.waitUntil(5_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isNotEmpty()
            }
            compose.mainClock.advanceTimeBy(96)
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(5_000) {
                compose.mainClock.advanceTimeBy(32)
                compose.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("media-pager").assertDoesNotExist()
            compose.onNodeWithTag("media-fixture:icloud:0020").assertIsDisplayed()
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun zoomedLandscapePhotoCanFillItsLetterbox() {
        loaded()
        val id = "fixture:icloud:0020"
        compose.onNodeWithTag("media-$id").performClick()
        viewerReady(id)
        val viewport = compose.onNodeWithTag("viewer-image-$id")
        val imageBounds = compose.onNodeWithTag("photo-viewer-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val viewportBounds = viewport.fetchSemanticsNode().boundsInRoot
        val x = (viewportBounds.width / 2).toInt()
        val y = (imageBounds.top - viewportBounds.top - 60).toInt()
        assertTrue(y > 0)
        val before = viewport.captureToImage().toPixelMap()[x, y]
        assertTrue("Expected black letterbox before zoom", before.red < .05f && before.green < .05f && before.blue < .05f)
        viewport.performTouchInput { doubleClick() }
        val after = viewport.captureToImage().toPixelMap()[x, y]
        assertTrue("Zoomed photo must extend beyond its original fitted rectangle", maxOf(after.red, after.green, after.blue) > .2f)
    }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag begins with $prefix") {
        it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith(prefix)
    }
}
