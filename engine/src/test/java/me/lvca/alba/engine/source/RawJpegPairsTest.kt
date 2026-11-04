package me.lvca.alba.engine.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A RAW is matched to the JPEG its camera saved beside it, by the names Pixel, Samsung and other apps give them. */
class RawJpegPairsTest {
    @Test
    fun pixelRawAndCoverJpegShareTheirStem() {
        assertEquals("pxl_20240501_101010123", RawJpegPairs.stem("PXL_20240501_101010123.RAW-02.ORIGINAL.dng"))
        assertTrue(RawJpegPairs.hasJpegCompanion(
            "PXL_20240501_101010123.RAW-02.ORIGINAL.dng",
            listOf("PXL_20240501_101010123.RAW-01.COVER.jpg"),
        ))
        assertTrue(RawJpegPairs.hasJpegCompanion(
            "PXL_20240501_101010123.RAW-02.ORIGINAL.dng",
            listOf("PXL_20240501_101010123.RAW-01.MP.COVER.jpg"),
        ))
    }

    @Test
    fun samsungAndOtherCameraAppsUseTheSameBaseName() {
        assertTrue(RawJpegPairs.hasJpegCompanion("20240501_101010.dng", listOf("20240501_101010.jpg")))
        assertTrue(RawJpegPairs.hasJpegCompanion("IMG_1234.DNG", listOf("img_1234.JPEG")))
    }

    @Test
    fun aDifferentShotOrANonJpegIsNotACompanion() {
        assertFalse(RawJpegPairs.hasJpegCompanion("20240501_101010.dng", listOf("20240501_101011.jpg", "20240501_101010.heic")))
        assertFalse(RawJpegPairs.hasJpegCompanion("20240501_101010.dng", listOf("20240501_101010_1.jpg")))
        assertFalse(RawJpegPairs.hasJpegCompanion("PXL_20240501_101010123.RAW-02.ORIGINAL.dng", emptyList()))
        assertFalse(RawJpegPairs.hasJpegCompanion(".dng", listOf(".jpg")))
    }
}
