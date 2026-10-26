package me.lvca.alba.app.gallery.grid

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import me.lvca.alba.app.testing.AlbaAppRule
import me.lvca.alba.app.testing.Smoke
import me.lvca.alba.app.testing.scrollToGalleryMedia
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Pinching morphs the grid live with the fingers, then commits past a fifth of the way or springs back. */
class GalleryPinchAnimationTest {
    @get:Rule val app = AlbaAppRule()
    private val compose get() = app.compose

    private val photo get() = compose.onNodeWithTag("media-fixture:icloud:0020")
    private val grid get() = compose.onNodeWithTag("gallery-grid")
    private fun width() = photo.fetchSemanticsNode().boundsInRoot.width

    /** The photo's row, across the middle of the grid: fingers either side of a first-column photo must stay on screen. */
    private fun center(): Offset {
        val area = grid.fetchSemanticsNode().boundsInRoot
        return Offset(area.width / 2f, photo.fetchSemanticsNode().boundsInRoot.center.y - area.top)
    }

    /** Two fingers [start] px either side of the photo, past touch slop at [slop], then held at [hold]. */
    private fun pinchTo(start: Float, slop: Float, hold: Float) {
        val center = center()
        grid.performTouchInput {
            down(0, center - Offset(start, 0f))
            down(1, center + Offset(start, 0f))
            for (spread in listOf(slop, hold)) {
                advanceEventTime(16)
                updatePointerTo(0, center - Offset(spread, 0f))
                updatePointerTo(1, center + Offset(spread, 0f))
                move()
            }
        }
        compose.mainClock.advanceTimeBy(48)
    }

    private fun release() {
        grid.performTouchInput { up(0); up(1) }
        compose.mainClock.advanceTimeBy(400)
    }

    @Smoke @Test fun pinchFollowsTheFingersThenCommitsOrReverts() {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        // Initial lazy-grid placement must settle before measuring the pinch anchor.
        compose.mainClock.advanceTimeBy(1200)
        val initial = width()
        val initialTop = photo.fetchSemanticsNode().boundsInRoot.top
        compose.mainClock.autoAdvance = false
        try {
            // A small spread already moves the grid with the fingers, but releasing it there reverts.
            pinchTo(start = 100f, slop = 140f, hold = 154f)
            val following = width()
            assertTrue("The grid must follow the fingers before release: $initial -> $following", following > initial + 2)
            release()
            assertEquals("A pinch below the commit point returns to the original level", initial, width(), 2f)

            // Most of the way: the tile is mid-morph while held and lands on the larger level once released.
            pinchTo(start = 100f, slop = 140f, hold = 210f)
            val intermediate = width()
            release()
            val large = width()
            assertTrue("The morph must have a real intermediate size: $initial < $intermediate < $large",
                intermediate > initial + 2 && intermediate < large - 2)
            assertEquals("Pinching keeps the touched row anchored", initialTop, photo.fetchSemanticsNode().boundsInRoot.top, 3f)

            // Pinching in steps back one level per gesture until the smallest level, which then holds.
            var previous = large
            repeat(2) {
                pinchTo(start = 260f, slop = 220f, hold = 90f)
                release()
                val now = width()
                assertTrue("Pinching in should reach a smaller level: $previous -> $now", now < previous - 2)
                previous = now
            }
            pinchTo(start = 260f, slop = 220f, hold = 90f)
            assertTrue("Past the last level the grid only rubber-bands", width() < previous)
            release()
            assertEquals("The smallest level remains stable at its limit", previous, width(), 2f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
