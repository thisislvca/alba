package me.lvca.alba.app.editing

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ColorMathTest {
    private fun argb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun Int.r() = this shr 16 and 255
    private fun Int.g() = this shr 8 and 255
    private fun Int.b() = this and 255
    private fun Int.a() = this ushr 24
    private val levels = (0..20).map { it / 20f }
    private val toneSliders = listOf(Adjustment.BRIGHTNESS, Adjustment.CONTRAST, Adjustment.HIGHLIGHTS, Adjustment.SHADOWS)

    private fun render(recipe: EditRecipe, pixels: IntArray, width: Int): IntArray =
        IntArray(pixels.size).also { RecipeRenderer(recipe, width, pixels.size / width).renderPixels(pixels, it, width) }

    @Test fun everythingAtZeroChangesNothing() {
        assertNull(ToneCurve.lut(Adjustments()))
        levels.forEach { assertEquals(it, ToneCurve.apply(it, Adjustments()), 1e-6f) }
        assertTrue(ColorMatrices.isIdentity(ColorMatrices.recipe(EditRecipe())))
        assertTrue(ColorMatrices.isIdentity(ColorMatrices.filter(LookFilter.SEPIA, strength = 0)))
        assertTrue(EditRecipe(filter = LookFilter.NOIR, filterStrength = 0).isIdentity)
        assertFalse(RecipeRenderer(EditRecipe(), 4, 4).changesPixels)
        val random = Random(7)
        val pixels = IntArray(64) { random.nextInt() }
        assertArrayEquals(pixels, render(EditRecipe(), pixels, 8))
    }

    @Test fun everyToneCurveRisesWithItsInputAndKeepsItsRange() {
        toneSliders.forEach { slider ->
            listOf(-100, -50, 50, 100).forEach { value ->
                val adjustments = Adjustments().with(slider, value)
                val curve = levels.map { ToneCurve.apply(it, adjustments) }
                curve.zipWithNext().forEach { (a, b) -> assertTrue("$slider $value is not monotonic", b >= a) }
                curve.forEach { assertTrue(it in 0f..1f) }
            }
        }
    }

    @Test fun toneSlidersMoveTheirTonesMonotonically() {
        val values = (-100..100 step 10).toList()
        fun series(slider: Adjustment, x: Float) = values.map { ToneCurve.apply(x, Adjustments().with(slider, it)) }
        listOf(Adjustment.BRIGHTNESS, Adjustment.HIGHLIGHTS, Adjustment.SHADOWS).forEach { slider ->
            series(slider, .5f).zipWithNext().forEach { (a, b) -> assertTrue("$slider", b >= a) }
        }
        assertTrue(series(Adjustment.HIGHLIGHTS, .8f).let { it.last() - it.first() } > series(Adjustment.HIGHLIGHTS, .2f).let { it.last() - it.first() })
        assertTrue(series(Adjustment.SHADOWS, .2f).let { it.last() - it.first() } > series(Adjustment.SHADOWS, .8f).let { it.last() - it.first() })
        val spread = values.map { v -> Adjustments(contrast = v).let { ToneCurve.apply(.75f, it) - ToneCurve.apply(.25f, it) } }
        spread.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
    }

    @Test fun colourSlidersMoveMonotonically() {
        val values = (-100..100 step 20).toList()
        val orange = argb(200, 120, 60)
        fun colour(adjustments: Adjustments) = ColorMatrices.apply(ColorMatrices.adjustments(adjustments), orange)
        values.map { colour(Adjustments(saturation = it)).let { p -> p.r() - p.b() } }.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
        values.map { colour(Adjustments(warmth = it)).let { p -> p.r() - p.b() } }.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
        values.map { colour(Adjustments(tint = it)).g() }.zipWithNext().forEach { (a, b) -> assertTrue(b <= a) }
        assertEquals("Saturation -100 is grey", colour(Adjustments(saturation = -100)).r(), colour(Adjustments(saturation = -100)).b())
    }

    @Test fun vignetteDarkensOrLightensOnlyTowardsTheCorners() {
        assertEquals(0f, Vignette.alpha(100, Vignette.distance(50, 50, 101, 101)), 1e-6f)
        assertEquals(1f, Vignette.distance(0, 0, 10_000, 10_000), .001f)
        (0..10).map { Vignette.alpha(100, it / 10f) }.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
        val grey = IntArray(21 * 21) { argb(128, 128, 128) }
        val dark = render(EditRecipe(Adjustments(vignette = 100)), grey, 21)
        val light = render(EditRecipe(Adjustments(vignette = -100)), grey, 21)
        assertEquals(128, dark[10 * 21 + 10].r())
        assertTrue(dark[0].r() < 60)
        assertTrue(light[0].r() > 200)
    }

    @Test fun sharpeningRaisesEdgeContrastAndLeavesFlatAreas() {
        val width = 8
        val edge = IntArray(width * 4) { if (it % width < width / 2) argb(80, 80, 80) else argb(160, 160, 160) }
        val sharp = render(EditRecipe(Adjustments(sharpness = 100)), edge, width)
        assertTrue(sharp[3].r() < 80 && sharp[4].r() > 160)
        assertEquals(80, sharp[0].r())
        assertEquals(160, sharp[width - 1].r())
    }

    @Test fun slidersClampToTheirRangeAndPixelsToEightBits() {
        assertEquals(100, Adjustments().with(Adjustment.CONTRAST, 400).contrast)
        assertEquals(-100, Adjustments().with(Adjustment.SHADOWS, -400).shadows)
        assertEquals(0, Adjustments().with(Adjustment.SHARPNESS, -50).sharpness)
        assertEquals(Adjustments(brightness = 100, sharpness = 0), Adjustments.of(listOf(250, 0, 0, 0, 0, 0, 0, 0, -3)))
        val random = Random(3)
        val pixels = IntArray(16 * 16) { argb(random.nextInt(256), random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        val extreme = EditRecipe(Adjustments(100, 100, 100, 100, 100, 100, 100, 100, 100), LookFilter.VIVID)
        render(extreme, pixels, 16).forEachIndexed { i, p ->
            assertEquals("Alpha is kept", pixels[i].a(), p.a())
            assertTrue(listOf(p.r(), p.g(), p.b()).all { it in 0..255 })
        }
        // White stays white under the strongest brightening: it clamps rather than wrapping round.
        val bright = EditRecipe(Adjustments(brightness = 100, contrast = 100, saturation = 100))
        assertEquals(argb(255, 255, 255), render(bright, intArrayOf(argb(255, 255, 255)), 1)[0])
        assertEquals(argb(0, 0, 0), render(bright, intArrayOf(argb(0, 0, 0)), 1)[0])
    }

    @Test fun everyLookChangesColourAndFadesWithStrength() {
        val sample = argb(180, 110, 70)
        LookFilter.entries.filter { it != LookFilter.NONE }.forEach { look ->
            val full = ColorMatrices.apply(ColorMatrices.filter(look), sample)
            assertNotEquals("$look changes colour", sample, full)
            val half = ColorMatrices.apply(ColorMatrices.filter(look, 50), sample)
            assertTrue("$look at half strength lies between", half.r() in minOf(sample.r(), full.r())..maxOf(sample.r(), full.r()))
        }
        val mono = ColorMatrices.apply(ColorMatrices.filter(LookFilter.MONO), sample)
        assertEquals(mono.r(), mono.g()); assertEquals(mono.g(), mono.b())
    }

    @Test fun rowRendererMatchesTheWholeImageAndReportsProgress() {
        val random = Random(11)
        val width = 13
        val pixels = IntArray(width * 70) { random.nextInt() or ALPHA_MASK }
        val recipe = EditRecipe(Adjustments(brightness = 30, contrast = 20, sharpness = 40, vignette = 50, saturation = 20), LookFilter.FILM, 60)
        val reference = render(recipe, pixels, width)
        val rows = mutableMapOf<Int, IntArray>()
        val progress = mutableListOf<Float>()
        RecipeRenderer(recipe, width, 70).render(
            read = { y, row -> pixels.copyInto(row, 0, y * width, y * width + width) },
            write = { y, row -> rows[y] = row.copyOf() },
            checkpoint = progress::add)
        assertArrayEquals(reference, (0 until 70).flatMap { rows.getValue(it).toList() }.toIntArray())
        assertEquals(1f, progress.last())
        progress.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
    }
}
