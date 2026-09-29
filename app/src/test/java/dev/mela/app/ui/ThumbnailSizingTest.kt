package dev.mela.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailSizingTest {
    @Test
    fun `decode buckets cap tile and detail work`() {
        assertEquals(0, thumbnailSizeBucket(0))
        assertEquals(256, thumbnailSizeBucket(200))
        assertEquals(512, thumbnailSizeBucket(300))
        assertEquals(1_024, thumbnailSizeBucket(900))
        assertEquals(2_048, thumbnailSizeBucket(1_400))
        assertEquals(2_048, thumbnailSizeBucket(5_000))
    }

    @Test
    fun `sampling keeps the crop dimension large enough`() {
        assertEquals(4, thumbnailSampleSize(width = 4_000, height = 3_000, targetSidePx = 512))
        assertEquals(1, thumbnailSampleSize(width = 4_000, height = 700, targetSidePx = 512))
        assertEquals(1, thumbnailSampleSize(width = 0, height = 0, targetSidePx = 512))
    }
}
