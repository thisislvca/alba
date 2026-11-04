package me.lvca.alba.engine.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A RAW is matched to the JPEG its camera saved beside it, by the names Pixel, Samsung and other apps give them. */
class RawJpegPairsTest {
    @Test
    fun pixelRawAndCoverJpegShareTheirBaseName() {
        assertEquals("PXL_20240501_101010123", RawJpegPairs.baseName("PXL_20240501_101010123.RAW-02.ORIGINAL.dng"))
        assertEquals("PXL_20240501_101010123.RAW-", RawJpegPairs.pixelCoverPrefix("PXL_20240501_101010123.RAW-02.ORIGINAL.dng"))
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
        assertEquals(listOf("img_1234.jpg", "img_1234.jpeg"), RawJpegPairs.sameBaseJpegNames("IMG_1234.DNG"))
    }

    @Test
    fun namesWhoseCaseChangesLengthStillCompareWithoutSlicing() {
        // "İ" lower-cases to two characters, so a length taken from a lower-cased name would cut the original wrongly.
        assertEquals("İMG_1", RawJpegPairs.baseName("İMG_1.dng"))
        assertTrue(RawJpegPairs.hasJpegCompanion("İMG_1.dng", listOf("İMG_1.jpg")))
        assertFalse(RawJpegPairs.hasJpegCompanion("Straße_1.DNG", listOf("STRASSE_1.jpg")))
    }

    @Test
    fun aDifferentShotOrANonJpegIsNotACompanion() {
        assertFalse(RawJpegPairs.hasJpegCompanion("20240501_101010.dng", listOf("20240501_101011.jpg", "20240501_101010.heic")))
        assertFalse(RawJpegPairs.hasJpegCompanion("20240501_101010.dng", listOf("20240501_101010_1.jpg")))
        assertFalse(RawJpegPairs.hasJpegCompanion("PXL_20240501_101010123.RAW-02.ORIGINAL.dng", emptyList()))
        assertFalse(RawJpegPairs.hasJpegCompanion(".dng", listOf(".jpg")))
        assertEquals(emptyList<String>(), RawJpegPairs.sameBaseJpegNames(".dng"))
    }
}
