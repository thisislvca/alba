package me.lvca.alba.app.ui.viewer

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinchToCloseTest {
    @Test fun backdropFadesOutOverThirtyFivePercentOfShrink() {
        assertEquals(1f, pinchClosePose(1f, Offset.Zero).backdrop, 1e-5f)
        assertEquals(.5f, pinchClosePose(.825f, Offset.Zero).backdrop, 1e-5f)
        assertEquals(0f, pinchClosePose(.65f, Offset.Zero).backdrop, 1e-5f)
        assertEquals("Shrinking further keeps the canvas clear", 0f, pinchClosePose(.3f, Offset.Zero).backdrop, 1e-5f)
        assertEquals("The photo is drawn at the fingers' scale", .8f, pinchClosePose(.8f, Offset(3f, 4f)).scale, 1e-5f)
        assertEquals(Offset(3f, 4f), pinchClosePose(.8f, Offset(3f, 4f)).translation)
    }

    @Test fun closesOnceTheBackdropIsHalfFaded() {
        assertFalse("A slight pinch returns to fit", shouldClosePinch(.9f))
        assertFalse(shouldClosePinch(.83f))
        assertTrue("GP closes at half the fade, scale 0.825", shouldClosePinch(.825f))
        assertTrue(shouldClosePinch(.5f))
    }

    @Test fun onlyAnInwardTwoFingerGestureAtFitStartsIt() {
        assertTrue(startsPinchToClose(zoom = 1f, pinchMotion = .95f, pressed = 2))
        assertTrue("A two-finger pan at fit moves the photo too", startsPinchToClose(zoom = 1f, pinchMotion = 1f, pressed = 2))
        assertFalse("Spreading the fingers zooms in", startsPinchToClose(zoom = 1f, pinchMotion = 1.05f, pressed = 2))
        assertFalse("A zoomed photo pinches back to fit first", startsPinchToClose(zoom = 1.5f, pinchMotion = .9f, pressed = 2))
        assertFalse(startsPinchToClose(zoom = 1f, pinchMotion = .9f, pressed = 1))
    }

    @Test fun thePointUnderTheFingersStaysUnderThem() {
        val anchor = Offset(200f, -300f)
        val tracker = PinchCloseTracker(anchor)
        tracker.update(.5f, Offset.Zero)
        assertEquals(.5f, tracker.scale, 1e-5f)
        // The photo shrinks around the viewport centre: the anchored point lands at anchor × scale + translation.
        assertEquals(anchor, anchor * tracker.scale + tracker.translation)
        tracker.update(1f, Offset(10f, 20f))
        assertEquals("The photo follows the fingers' pan", anchor + Offset(10f, 20f), anchor * tracker.scale + tracker.translation)
    }

    @Test fun scaleStaysBetweenTheFloorAndFit() {
        val tracker = PinchCloseTracker(Offset.Zero)
        tracker.update(1.4f, Offset.Zero)
        assertEquals("Spreading back past fit stops at fit", 1f, tracker.scale, 0f)
        tracker.update(.01f, Offset.Zero)
        assertEquals(ViewerSpec.PINCH_CLOSE_MIN_SCALE, tracker.scale, 0f)
    }
}
