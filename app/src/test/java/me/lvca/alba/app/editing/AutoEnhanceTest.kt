package me.lvca.alba.app.editing

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AutoEnhanceTest {
    private fun stats(levels: IntRange, saturation: Float = .5f) = ImageStats(IntArray(256) { if (it in levels) 100 else 0 }, saturation)
    private fun enhance(levels: IntRange, saturation: Float = .5f) = AutoEnhance.adjustments(stats(levels, saturation))
    private fun tone(level: Int, adjustments: Adjustments) = ToneCurve.apply(level / 255f, adjustments)

    @Test fun aWellExposedFullRangePhotoIsLeftAlone() {
        assertEquals(Adjustments(), enhance(0..255))
        assertEquals(Adjustments(), AutoEnhance.adjustments(ImageStats(IntArray(256), .5f)))
    }

    @Test fun aDarkPhotoIsBrightenedAndItsShadowsOpened() {
        val result = enhance(0..90)
        assertTrue(result.brightness > 0)
        assertTrue(result.shadows > 0)
        assertEquals(0, result.highlights)
        // The median moves towards mid-grey.
        assertTrue(abs(tone(45, result) - .5f) < abs(45 / 255f - .5f))
    }

    @Test fun aBrightPhotoIsDarkenedAndItsHighlightsRecovered() {
        val result = enhance(170..255)
        assertTrue(result.brightness < 0)
        assertTrue(result.highlights < 0)
        assertEquals(0, result.shadows)
        assertTrue(abs(tone(212, result) - .5f) < abs(212 / 255f - .5f))
    }

    @Test fun aFlatPhotoGetsContrastWithoutAShift() {
        val result = enhance(100..155)
        assertTrue(result.contrast > 0)
        assertTrue(abs(result.brightness) <= 5)
        val before = 155 - 100
        val after = (tone(155, result) - tone(100, result)) * 255
        assertTrue("The range widens", after > before * 1.3f)
    }

    @Test fun dullColourIsLiftedButGreyAndVividColourAreNot() {
        assertTrue(enhance(0..255, saturation = .1f).saturation > 0)
        assertEquals(0, enhance(0..255, saturation = 0f).saturation)
        assertEquals(0, enhance(0..255, saturation = .6f).saturation)
        assertTrue(enhance(0..255, saturation = .1f).saturation <= 30)
    }

    @Test fun enhanceKeepsTheUsersOtherSlidersAndCanBeTakenOff() {
        val enhanced = enhance(0..90, saturation = .1f)
        val mine = Adjustments(warmth = 40, vignette = 20, brightness = -10)
        val applied = AutoEnhance.applyTo(mine, enhanced)
        assertEquals(40, applied.warmth); assertEquals(20, applied.vignette)
        assertEquals(enhanced.brightness, applied.brightness)
        assertTrue(AutoEnhance.isApplied(applied, enhanced))
        assertFalse(AutoEnhance.isApplied(mine, enhanced))
        assertEquals(Adjustments(warmth = 40, vignette = 20), AutoEnhance.remove(applied))
    }

    @Test fun statisticsReadLumaAndSaturation() {
        val stats = ImageStats.of(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF808080.toInt()))
        assertEquals(1, stats.lumaHistogram[0]); assertEquals(1, stats.lumaHistogram[255]); assertEquals(1, stats.lumaHistogram[128])
        assertEquals(.25f, stats.meanSaturation, 1e-6f)
    }
}
