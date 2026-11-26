package me.lvca.alba.app.gallery

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.testing.AlbaAppRule
import me.lvca.alba.app.testing.openGalleryMedia
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seen once on a phone: after flinging far back, opening a photo and returning, the grid's top could not be reached
 * again; it stopped at a row under the large title, with the shared albums strip and the newest months out of reach.
 */
@RunWith(AndroidJUnit4::class)
class GalleryReturnScrollTest {
    @get:Rule val app = AlbaAppRule()
    private val compose get() = app.compose

    private fun visibleTiles() = compose.onAllNodes(SemanticsMatcher("gallery media tile") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("media-") == true
    }).fetchSemanticsNodes().filter { it.boundsInRoot.height > 0 }

    @Test fun theTopStaysReachableAfterFlingingBackOpeningAPhotoAndReturning() {
        compose.onNodeWithTag("shared-albums-strip").assertIsDisplayed()
        // Fling far back, as fast as a thumb does: the header hides and the grid runs on after the finger lifts.
        repeat(FLINGS) { compose.onNodeWithTag("gallery-grid").performTouchInput { swipeUp(durationMillis = FLING_MILLIS) } }
        compose.waitForIdle()
        compose.onNodeWithTag("shared-albums-strip").assertDoesNotExist()
        val opened = visibleTiles().map { it.config[SemanticsProperties.TestTag].removePrefix("media-") }
            .first { it.startsWith("fixture:") }
        compose.openGalleryMedia(opened)
        compose.onNodeWithTag("floating-viewer-back").performClick()
        compose.waitUntil(LONG) { compose.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("media-$opened").assertIsDisplayed()

        // Back to the top by hand, the way a person does it: fling down until the grid stops moving.
        repeat(FLINGS * 2) { compose.onNodeWithTag("gallery-grid").performTouchInput { swipeDown(durationMillis = FLING_MILLIS) } }
        compose.waitForIdle()
        compose.onNodeWithTag("shared-albums-strip").assertIsDisplayed()
        val strip = compose.onNodeWithTag("shared-albums-strip").fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("gallery-header").fetchSemanticsNode().boundsInRoot
        assertTrue("The shared albums strip ($strip) starts below the header ($header)", strip.top >= header.bottom - 1f)
    }

    /** Returning from a photo further on than the one opened scrolls the grid to it; the top must stay reachable. */
    @Test fun theTopStaysReachableAfterReturningFromAnotherPhoto() {
        repeat(FLINGS) { compose.onNodeWithTag("gallery-grid").performTouchInput { swipeUp(durationMillis = FLING_MILLIS) } }
        compose.waitForIdle()
        val opened = visibleTiles().map { it.config[SemanticsProperties.TestTag].removePrefix("media-") }
            .first { it.startsWith("fixture:") }
        compose.openGalleryMedia(opened)
        repeat(PAGES) { compose.onNodeWithTag("media-pager").performTouchInput { swipeLeft() }; compose.waitForIdle() }
        compose.onNodeWithTag("floating-viewer-back").performClick()
        compose.waitUntil(LONG) { compose.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isEmpty() }

        compose.onNodeWithTag("gallery-grid").performScrollToIndex(0)
        repeat(2) { compose.onNodeWithTag("gallery-grid").performTouchInput { swipeDown(durationMillis = FLING_MILLIS) } }
        compose.waitForIdle()
        compose.onNodeWithTag("shared-albums-strip").assertIsDisplayed()
        val strip = compose.onNodeWithTag("shared-albums-strip").fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("gallery-header").fetchSemanticsNode().boundsInRoot
        assertTrue("The shared albums strip ($strip) starts below the header ($header)", strip.top >= header.bottom - 1f)
    }

    private companion object {
        const val PAGES = 8
        const val FLINGS = 6
        const val FLING_MILLIS = 80L
        const val LONG = 10_000L
    }
}
