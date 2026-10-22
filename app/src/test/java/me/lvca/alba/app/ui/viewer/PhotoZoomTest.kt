package me.lvca.alba.app.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoZoomTest {
    private val portraitPhone = IntSize(1080, 2400)
    private val landscapePhoto = IntSize(2000, 1500)

    @Test fun maxZoomStopsAtOneHundredFiftySourcePixelsPerInch() {
        // A 4032px-wide photo fitted to 1080px at 420dpi: 4032 × 420 / (1080 × 150) ≈ 10.45×.
        assertEquals(10.45f, maxPhotoZoom(IntSize(4032, 3024), landscapePhoto, portraitPhone, 420f), .01f)
    }

    @Test fun maxZoomIsNeverBelowTwoOrTheFillScale() {
        assertEquals(2f, maxPhotoZoom(IntSize(400, 300), IntSize(400, 300), IntSize(1080, 1080), 420f), 1e-4f)
        // Filling a tall screen with a landscape photo needs (2400 / 1500) / (1080 / 2000) ≈ 2.96×.
        assertEquals(2.963f, maxPhotoZoom(IntSize(600, 450), landscapePhoto, portraitPhone, 420f), .01f)
    }

    @Test fun maxZoomFallsBackWhenTheSourceSizeIsUnknown() {
        assertEquals(ViewerSpec.UNKNOWN_MAX_ZOOM, maxPhotoZoom(IntSize.Zero, IntSize(1000, 1000), IntSize(1080, 1080), 420f), 1e-4f)
    }

    @Test fun detentHoldsOneTimesUntilEightDpPastTheWindow() {
        val fittedLong = 1000f
        val density = 1f
        assertTrue(zoomDetentSnapped(1.015f, wasSnapped = false, fittedLongPx = fittedLong, density = density))
        assertFalse(zoomDetentSnapped(1.02f, wasSnapped = false, fittedLongPx = fittedLong, density = density))
        assertTrue("Snapped holds 8dp beyond the +16dp edge", zoomDetentSnapped(1.022f, wasSnapped = true, fittedLongPx = fittedLong, density = density))
        assertFalse(zoomDetentSnapped(1.025f, wasSnapped = true, fittedLongPx = fittedLong, density = density))
        assertTrue(zoomDetentSnapped(.975f, wasSnapped = false, fittedLongPx = fittedLong, density = density))
        assertFalse(zoomDetentSnapped(.96f, wasSnapped = true, fittedLongPx = fittedLong, density = density))
    }

    @Test fun zoomingAroundAPointKeepsItStill() {
        val focus = Offset(120f, -40f)
        val offset = zoomAroundOffset(Offset.Zero, 1f, 2.5f, focus)
        // A photo point under the focus at 1× is at focus; after zooming it maps to offset + focus × 2.5.
        assertEquals(focus, offset + focus * 2.5f)
    }

    @Test fun doubleTapOnTheLetterboxZoomsTowardThePhotoEdge() {
        // A square photo is fitted to 1080 × 1080, centred in a 2400px-tall viewport (top edge at y = 660).
        val square = IntSize(2000, 2000)
        val (zoom, nearEdge) = doubleTapZoomTarget(Offset(540f, 670f), portraitPhone, square, 1f, Offset.Zero, 10f)
        val (_, onLetterbox) = doubleTapZoomTarget(Offset(540f, 100f), portraitPhone, square, 1f, Offset.Zero, 10f)
        assertEquals(2.5f, zoom, 1e-5f)
        // At 2.5× the photo is 2700px tall, so it can move 150px; it lands on the edge the tap was nearest.
        assertEquals(Offset(0f, 150f), nearEdge)
        assertEquals("A letterbox tap ends where a tap on the nearest photo edge does", nearEdge, onLetterbox)
        val (back, reset) = doubleTapZoomTarget(Offset(540f, 100f), portraitPhone, square, 2.5f, nearEdge, 10f)
        assertEquals(1f, back, 1e-5f)
        assertEquals(Offset.Zero, reset)
    }

    @Test fun doubleTapNeverExceedsTheMaximum() {
        assertEquals(2f, doubleTapZoomTarget(Offset(540f, 1200f), portraitPhone, landscapePhoto, 1f, Offset.Zero, 2f).first, 1e-5f)
    }
}
