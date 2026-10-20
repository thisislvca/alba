package me.lvca.alba.app.gallery.grid

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.scrollToGalleryMedia
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GalleryPinchAnimationTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }

    private val photo get() = compose.onNodeWithTag("media-fixture:icloud:0020")
    private val grid get() = compose.onNodeWithTag("gallery-grid")
    private fun width() = photo.fetchSemanticsNode().boundsInRoot.width
    private fun pinch(out: Boolean) {
        val center = photo.fetchSemanticsNode().boundsInRoot.center - grid.fetchSemanticsNode().boundsInRoot.topLeft
        val start = if (out) 45f else 80f
        val end = if (out) 80f else 45f
        grid.performTouchInput {
            down(0, center - Offset(start, 0f))
            down(1, center + Offset(start, 0f))
            moveTo(0, center - Offset(end, 0f), delayMillis = 16)
            moveTo(1, center + Offset(end, 0f), delayMillis = 16)
            up(0); up(1)
        }
    }
    @Test fun pinchResizesThroughIntermediateFramesAndCanReverse() {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        grid.performScrollToKey("fixture:icloud:0020")
        // Initial lazy-grid placement must settle before measuring the pinch anchor.
        compose.mainClock.advanceTimeBy(1200)
        val initial = width()
        val initialTop = photo.fetchSemanticsNode().boundsInRoot.top
        compose.mainClock.autoAdvance = false
        try {
            pinch(out = true)
            compose.mainClock.advanceTimeBy(96)
            compose.waitForIdle()
            val growing = width()
            assertTrue("Pinch must render a larger intermediate tile, not jump to its final width: $initial -> $growing", growing > initial + 2)
            // Reverse while the spring is still moving, rather than waiting for it to finish.
            pinch(out = false)
            compose.mainClock.advanceTimeBy(32)
            val reversing = width()
            assertTrue("Reversal should start from the visible size", kotlin.math.abs(reversing - growing) < initial * .3f)
            compose.mainClock.advanceTimeBy(1200)
            assertEquals("Reverse pinch returns to the original density", initial, width(), 2f)
            pinch(out = true)
            compose.mainClock.advanceTimeBy(96)
            val intermediate = width()
            compose.mainClock.advanceTimeBy(1200)
            val large = width()
            assertTrue("The animation must have a real intermediate size: $initial < $intermediate < $large", intermediate > initial + 2 && intermediate < large - 2)
            assertEquals("Pinching should keep the touched row anchored", initialTop, photo.fetchSemanticsNode().boundsInRoot.top, 3f)
            pinch(out = false)
            compose.mainClock.advanceTimeBy(1200)
            pinch(out = false)
            compose.mainClock.advanceTimeBy(96)
            val shrinking = width()
            compose.mainClock.advanceTimeBy(1200)
            val small = width()
            assertTrue("Pinching in should animate down to the small grid", small < shrinking && shrinking < initial)
            pinch(out = false)
            compose.mainClock.advanceTimeBy(1200)
            assertEquals("Smallest density remains stable at its limit", small, width(), 2f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
