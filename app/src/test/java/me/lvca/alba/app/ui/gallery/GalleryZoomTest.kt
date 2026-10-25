package me.lvca.alba.app.ui.gallery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

class GalleryZoomTest {
    @Test fun levelsMatchGooglePhotosColumnCounts() {
        // A 411dp phone: compact 5, day 3 (under 413dp), cozy 2, one photo per row.
        assertEquals(listOf(5, 3, 2, 1), GridLevel.entries.map { it.columns(411f, 411f) })
        // From 413dp the day level has 4 columns, and cozy one fewer.
        assertEquals(listOf(5, 4, 3, 1), GridLevel.entries.map { it.columns(420f, 420f) })
        assertEquals(listOf(4, 3, 2, 1), GridLevel.entries.map { it.columns(360f, 360f) })
        // Landscape keeps the portrait tile size by scaling with the longer side.
        assertEquals(10, GridLevel.COMPACT.columns(411f, 822f))
    }

    @Test fun pinchStepsOneLevelAndStopsAtTheEnds() {
        assertEquals(GridLevel.DAY, GridLevel.COMPACT.next(zoomIn = true))
        assertEquals(GridLevel.COMPACT, GridLevel.DAY.next(zoomIn = false))
        assertNull(GridLevel.COMPACT.next(zoomIn = false))
        assertNull(GridLevel.FIT.next(zoomIn = true))
        assertTrue(GridLevel.DAY.dayHeaders && !GridLevel.COMPACT.dayHeaders)
    }

    @Test fun progressFollowsTheFingersAndCommitsPastOneFifth() {
        assertEquals(0f, pinchProgress(1f), 0f)
        assertEquals(1f, pinchProgress(1.8f), 1e-5f)
        assertEquals(1f, pinchProgress(.36f), 1e-5f)
        assertEquals(.5f, pinchProgress(1.4f), 1e-5f)
        // The commit point sits near a finger scale of 1.16 zooming in and 0.872 zooming out.
        assertEquals(GalleryFeel.PINCH_COMMIT_PROGRESS, pinchProgress(1.16f), 1e-4f)
        assertEquals(GalleryFeel.PINCH_COMMIT_PROGRESS, pinchProgress(.872f), 1e-4f)
    }

    @Test fun rubberBandResistsPastTheLastLevel() {
        assertEquals(1f, pinchRubberBand(1f), 1e-6f)
        assertTrue(pinchRubberBand(.5f) in .83f..1f)
        assertEquals(5f / 6f, pinchRubberBand(0f), 1e-5f)
        assertTrue(pinchRubberBand(2f) in 1f..1.21f)
    }

    @Test fun morphStartsAtTheOldLayoutAndEndsExactlyOnTheNewOne() {
        val anchorFrom = rect(100f, 200f, 80f)
        val anchorTo = rect(0f, 200f, 135f)
        val tile = rect(182f, 200f, 80f)
        val target = rect(137f, 200f, 135f)
        assertEquals(ItemPose(182f, 200f, 1f, 1f), morphPose(tile, target, anchorFrom, anchorTo, 0f, incoming = false, header = false))
        val end = morphPose(tile, target, anchorFrom, anchorTo, 1f, incoming = false, header = false)
        assertEquals(137f, end.left, 1e-3f); assertEquals(200f, end.top, 1e-3f); assertEquals(135f / 80f, end.scale, 1e-4f)
        // The anchor itself travels to its new place.
        val anchor = morphPose(anchorFrom, anchorTo, anchorFrom, anchorTo, 1f, incoming = false, header = false)
        assertEquals(anchorTo.left, anchor.left, 1e-3f)
    }

    @Test fun itemsWithoutACounterpartFollowTheAnchorAndFade() {
        val anchorFrom = rect(0f, 0f, 100f)
        val anchorTo = rect(0f, 0f, 200f)
        val gone = morphPose(rect(100f, 100f, 100f), null, anchorFrom, anchorTo, 1f, incoming = false, header = false)
        assertEquals(ItemPose(200f, 200f, 2f, 0f), gone)
        // Headers cross over: the old one is gone at half way, the new one only starts then.
        assertEquals(0f, morphPose(rect(0f, 50f, 100f), null, anchorFrom, anchorTo, .5f, incoming = false, header = true).alpha, 1e-6f)
        assertEquals(0f, morphPose(rect(0f, 50f, 100f), null, anchorFrom, anchorTo, .5f, incoming = true, header = true).alpha, 1e-6f)
        // An incoming tile with no counterpart grows into place.
        val arriving = morphPose(rect(200f, 0f, 200f), null, anchorFrom, anchorTo, 0f, incoming = true, header = false)
        assertEquals(ItemPose(100f, 0f, .5f, 0f), arriving)
        assertEquals(ItemPose(200f, 0f, 1f, 1f), morphPose(rect(200f, 0f, 200f), null, anchorFrom, anchorTo, 1f, incoming = true, header = false))
        // One that the old layout already draws stays hidden.
        assertEquals(0f, morphPose(rect(0f, 0f, 200f), rect(0f, 0f, 100f), anchorFrom, anchorTo, .7f, incoming = true, header = false).alpha, 0f)
    }

    @Test fun anchorIsTheNearestPhotoAndDragPrefersTheRow() {
        val items = listOf("a" to rect(0f, 0f, 100f), "b" to rect(102f, 0f, 100f), "c" to rect(0f, 102f, 100f))
        assertEquals("b", nearestByCentre(items, Offset(140f, 40f)))
        // Between two tiles of a row, the row wins over a closer tile in the next row.
        assertEquals("a", nearestInRow(items, Offset(100.5f, 98f)))
        assertEquals("c", nearestInRow(items, Offset(50f, 150f)))
        assertEquals("b", nearestInRow(items, Offset(400f, 10f)))
        assertNull(nearestInRow(emptyList<Pair<String, Rect>>(), Offset.Zero))
    }

    @Test fun dateHeadersAreRecognisedByKey() {
        assertTrue(isDateHeader("month-2024-03"))
        assertTrue(isDateHeader("day-2024-03-02"))
        assertFalse(isDateHeader("fixture:icloud:0001"))
    }

    private fun rect(left: Float, top: Float, side: Float) = Rect(Offset(left, top), Size(side, side))
}
