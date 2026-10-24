package me.lvca.alba.app.ui.viewer

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickScaleTest {
    private val slop = 8f
    private val distance = 400f

    @Test fun staysATapUntilTheFingerPassesSlop() {
        val tracker = QuickScaleTracker(slop, distance)
        assertEquals(1f, tracker.move(Offset(0f, 5f)), 0f)
        assertFalse(tracker.scaling)
        assertEquals(1f, tracker.move(Offset(2f, -2f)), 0f)
        assertFalse("A released second tap still toggles", tracker.scaling)
    }

    @Test fun draggingDownZoomsInAndUpZoomsOut() {
        val tracker = QuickScaleTracker(slop, distance)
        tracker.move(Offset(0f, 10f))
        assertTrue(tracker.scaling)
        assertTrue(tracker.move(Offset(0f, 40f)) > 1f)
        assertTrue(tracker.move(Offset(0f, -40f)) < 1f)
    }

    @Test fun aDragBackReturnsToTheSameZoom() {
        val tracker = QuickScaleTracker(slop, distance)
        tracker.move(Offset(0f, 10f))
        val zoom = listOf(30f, 70f, 120f, -120f, -70f, -30f).fold(1f) { acc, dy -> acc * tracker.move(Offset(0f, dy)) }
        assertEquals(1f, zoom, 1e-4f)
    }

    @Test fun aSidewaysDragIsLeftToThePager() {
        val tracker = QuickScaleTracker(slop, distance)
        tracker.move(Offset(12f, 3f))
        assertTrue(tracker.abandoned)
        assertFalse(tracker.scaling)
        assertEquals(1f, tracker.move(Offset(0f, 50f)), 0f)
    }

    @Test fun rateIsAboutTwoAndAHalfTimesPerOneHundredFiftyDp() {
        assertEquals(2.55f, quickScaleFactor(150f, 160f), .01f)
        assertEquals(1f, quickScaleFactor(0f, 160f), 0f)
        assertEquals(1f, quickScaleFactor(10f, 0f), 0f)
    }
}
