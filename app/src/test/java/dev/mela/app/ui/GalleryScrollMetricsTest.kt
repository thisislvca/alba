package dev.mela.app.ui

import org.junit.Assert.*
import org.junit.Test

class GalleryScrollMetricsTest {
    @Test fun sparseMonthsScrubWithinRowsInsteadOfSnappingToPhotos() {
        // Three sparse months, each with a full-span heading and a single photo row.
        val metrics = GalleryScrollMetrics(12, 3, 100f, 2f, mapOf(0 to 40f, 4 to 40f, 8 to 40f), 120, 0)
        val positions = (0..100).map { step ->
            val (index, offset) = metrics.target(step / 100f)
            metrics.position(index, offset)
        }
        assertTrue(positions.zipWithNext().all { (a, b) -> b >= a && b - a <= 4f })
        assertEquals(metrics.maxScroll, positions.last(), 1f)
        assertEquals(1 to 50, metrics.target(92f / metrics.maxScroll))
    }
    @Test fun headersAndPartialRowsPreserveYearLocationsAndEndpoints() {
        val metrics = GalleryScrollMetrics(10, 3, 100f, 2f, mapOf(0 to 200f, 1 to 40f, 4 to 40f, 9 to 60f), 150, 50)
        assertEquals(0f, metrics.topOf(0), 0f)
        assertEquals(202f, metrics.topOf(1), 0f)
        assertEquals(346f, metrics.topOf(4), 0f)
        assertEquals(metrics.topOf(5), metrics.topOf(7), 0f)
        assertTrue(metrics.topOf(8) > metrics.topOf(7))
        assertEquals(0 to 0, metrics.target(-1f))
        assertEquals(metrics.target(1f), metrics.target(2f))
    }
    @Test fun shortLibraryHasNoScrollableRange() {
        val metrics = GalleryScrollMetrics(3, 3, 100f, 2f, emptyMap(), 600, 0)
        assertEquals(0f, metrics.maxScroll, 0f)
        assertEquals(0 to 0, metrics.target(1f))
    }
}
