package dev.mela.app

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class GalleryPinchAnimationTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

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
    private fun capture(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = compose.activity.getExternalFilesDir("pinch-animation")!!.apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun pinchResizesThroughIntermediateFramesAndCanReverse() {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("media-fixture:icloud:0020").fetchSemanticsNodes().isNotEmpty() }
        grid.performScrollToKey("fixture:icloud:0020")
        // Initial lazy-grid placement must settle before measuring the pinch anchor.
        compose.mainClock.advanceTimeBy(1200)
        capture("01-medium")
        val initial = width()
        val initialTop = photo.fetchSemanticsNode().boundsInRoot.top
        compose.mainClock.autoAdvance = false
        try {
            pinch(out = true)
            compose.mainClock.advanceTimeBy(96)
            compose.waitForIdle()
            val growing = width()
            capture("02-growing")
            assertTrue("Pinch must render a larger intermediate tile, not jump to its final width: $initial -> $growing", growing > initial + 2)
            // Reverse while the spring is still moving, rather than waiting for it to finish.
            pinch(out = false)
            compose.mainClock.advanceTimeBy(32)
            val reversing = width()
            assertTrue("Reversal should start from the visible size", kotlin.math.abs(reversing - growing) < initial * .3f)
            compose.mainClock.advanceTimeBy(1200)
            assertEquals("Reverse pinch returns to the original density", initial, width(), 2f)
            capture("03-reversed")
            pinch(out = true)
            compose.mainClock.advanceTimeBy(96)
            val intermediate = width()
            compose.mainClock.advanceTimeBy(1200)
            val large = width()
            assertTrue("The animation must have a real intermediate size: $initial < $intermediate < $large", intermediate > initial + 2 && intermediate < large - 2)
            assertEquals("Pinching should keep the touched row anchored", initialTop, photo.fetchSemanticsNode().boundsInRoot.top, 3f)
            capture("04-large")
            pinch(out = false)
            compose.mainClock.advanceTimeBy(1200)
            pinch(out = false)
            compose.mainClock.advanceTimeBy(96)
            val shrinking = width()
            compose.mainClock.advanceTimeBy(1200)
            val small = width()
            assertTrue("Pinching in should animate down to the small grid", small < shrinking && shrinking < initial)
            capture("05-small")
            pinch(out = false)
            compose.mainClock.advanceTimeBy(1200)
            assertEquals("Smallest density remains stable at its limit", small, width(), 2f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
