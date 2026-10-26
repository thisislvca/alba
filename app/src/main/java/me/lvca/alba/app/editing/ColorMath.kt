package me.lvca.alba.app.editing

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * The editor's colour maths, in plain Kotlin so the JVM tests can check it. The preview and the saved copy share it:
 * the tone curve and sharpening run on the CPU (on a downscaled bitmap while editing), and the colour matrix uses
 * Android's ColorMatrix layout so the preview can hand the same numbers to the GPU.
 */

const val MAX_CHANNEL = 255
private const val LEVELS = 256
private const val HALF = 0.5f
private const val CHANNEL_MID = 127.5f

// Highlights and shadows bend one end of the curve. Below 1, the curve keeps a positive slope at every slider value.
private const val TONE_BEND = 0.96f

// Brightness is a gamma and contrast a slope about mid-grey, each from 1/1.74 to 1.74 times.
private const val TONE_RANGE = 0.8f

/** The tone curve: highlights, shadows, brightness then contrast, mapping 0..1 to 0..1 and never decreasing. */
object ToneCurve {
    fun apply(x: Float, adjustments: Adjustments): Float {
        val h = adjustments.highlights / SLIDER_LIMIT.toFloat()
        val s = adjustments.shadows / SLIDER_LIMIT.toFloat()
        val b = adjustments.brightness / SLIDER_LIMIT.toFloat()
        val c = adjustments.contrast / SLIDER_LIMIT.toFloat()
        var y = x.coerceIn(0f, 1f)
        y += TONE_BEND * h * y * y * (1 - y)
        y += TONE_BEND * s * y * (1 - y) * (1 - y)
        y = y.pow(2f.pow(-b * TONE_RANGE))
        y = HALF + (y - HALF) * 2f.pow(c * TONE_RANGE)
        return y.coerceIn(0f, 1f)
    }

    /** The curve for 8-bit channels, or null when it changes nothing. */
    fun lut(adjustments: Adjustments): IntArray? {
        if (listOf(adjustments.brightness, adjustments.contrast, adjustments.highlights, adjustments.shadows).all { it == 0 }) return null
        return IntArray(LEVELS) { (apply(it / MAX_CHANNEL.toFloat(), adjustments) * MAX_CHANNEL).roundToInt() }
    }
}

/** 4×5 colour matrices in Android's ColorMatrix layout: each row is R, G, B, A weights and an offset in 0..255. */
object ColorMatrices {
    private const val SIZE = 20
    private const val ROW = 5
    private const val OFFSET = 4
    private const val LUMA_R = 0.2126f
    private const val LUMA_G = 0.7152f
    private const val LUMA_B = 0.0722f
    private const val WARMTH_GAIN = 0.12f
    private const val TINT_GAIN = 0.1f
    private const val EPSILON = 1e-4f

    val IDENTITY: FloatArray get() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)

    /** [outer] applied after [inner]. */
    fun concat(outer: FloatArray, inner: FloatArray): FloatArray = FloatArray(SIZE) { index ->
        val row = index / ROW
        val column = index % ROW
        var sum = if (column == OFFSET) outer[row * ROW + OFFSET] else 0f
        for (k in 0 until OFFSET) sum += outer[row * ROW + k] * inner[k * ROW + column]
        sum
    }

    fun chain(vararg matrices: FloatArray): FloatArray = matrices.fold(IDENTITY) { result, next -> concat(next, result) }

    fun lerp(from: FloatArray, to: FloatArray, amount: Float): FloatArray = FloatArray(SIZE) { from[it] + (to[it] - from[it]) * amount }

    fun saturation(amount: Float): FloatArray {
        val r = LUMA_R * (1 - amount)
        val g = LUMA_G * (1 - amount)
        val b = LUMA_B * (1 - amount)
        return floatArrayOf(r + amount, g, b, 0f, 0f, r, g + amount, b, 0f, 0f, r, g, b + amount, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    fun gains(r: Float, g: Float, b: Float): FloatArray = floatArrayOf(r, 0f, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)

    /** A slope of [slope] about mid-grey, lifted by [lift] channel steps. */
    fun contrast(slope: Float, lift: Float = 0f): FloatArray {
        val offset = CHANNEL_MID * (1 - slope) + lift
        return floatArrayOf(slope, 0f, 0f, 0f, offset, 0f, slope, 0f, 0f, offset, 0f, 0f, slope, 0f, offset, 0f, 0f, 0f, 1f, 0f)
    }

    fun offsets(r: Float, g: Float, b: Float): FloatArray = floatArrayOf(1f, 0f, 0f, 0f, r, 0f, 1f, 0f, 0f, g, 0f, 0f, 1f, 0f, b, 0f, 0f, 0f, 1f, 0f)

    /** Saturation, warmth and tint. The tone sliders are a curve, not a matrix. */
    fun adjustments(adjustments: Adjustments): FloatArray {
        val s = adjustments.saturation / SLIDER_LIMIT.toFloat()
        val w = adjustments.warmth / SLIDER_LIMIT.toFloat()
        val t = adjustments.tint / SLIDER_LIMIT.toFloat()
        return chain(saturation(1 + s), gains(1 + WARMTH_GAIN * w, 1f, 1 - WARMTH_GAIN * w),
            gains(1 + TINT_GAIN * t / 2, 1 - TINT_GAIN * t, 1 + TINT_GAIN * t / 2))
    }

    fun filter(filter: LookFilter, strength: Int = FULL_STRENGTH): FloatArray =
        lerp(IDENTITY, Looks.matrix(filter), strength.coerceIn(0, FULL_STRENGTH) / FULL_STRENGTH.toFloat())

    /** Everything colour does after the tone curve: the adjustment sliders, then the look. */
    fun recipe(recipe: EditRecipe): FloatArray =
        concat(filter(recipe.filter, recipe.filterStrength), adjustments(recipe.adjustments))

    fun isIdentity(matrix: FloatArray): Boolean = matrix.indices.all { abs(matrix[it] - IDENTITY[it]) < EPSILON }

    fun apply(matrix: FloatArray, argb: Int): Int {
        val r = argb shr RED and MAX_CHANNEL
        val g = argb shr GREEN and MAX_CHANNEL
        val b = argb and MAX_CHANNEL
        fun row(i: Int) = (matrix[i] * r + matrix[i + 1] * g + matrix[i + 2] * b + matrix[i + OFFSET]).roundToInt().coerceIn(0, MAX_CHANNEL)
        return (argb and ALPHA_MASK) or (row(0) shl RED) or (row(ROW) shl GREEN) or row(2 * ROW)
    }
}

internal const val ALPHA_MASK = -0x1000000
internal const val RED = 16
internal const val GREEN = 8

/** The looks, each a small chain of the primitives so they stay easy to tune. */
private object Looks {
    private const val SEPIA_R = 0.393f
    private const val SEPIA_RG = 0.769f
    private const val SEPIA_RB = 0.189f
    private const val SEPIA_G = 0.349f
    private const val SEPIA_GG = 0.686f
    private const val SEPIA_GB = 0.168f
    private const val SEPIA_B = 0.272f
    private const val SEPIA_BG = 0.534f
    private const val SEPIA_BB = 0.131f

    @Suppress("MagicNumber") // Each look is a hand-tuned set of coefficients; naming each would not make them clearer.
    fun matrix(filter: LookFilter): FloatArray = with(ColorMatrices) {
        when (filter) {
            LookFilter.NONE -> IDENTITY
            LookFilter.VIVID -> chain(saturation(1.35f), contrast(1.1f))
            LookFilter.WARM -> chain(gains(1.08f, 1.0f, 0.88f), saturation(1.08f))
            LookFilter.COOL -> chain(gains(0.92f, 1.0f, 1.1f), saturation(0.95f))
            LookFilter.FILM -> chain(saturation(0.88f), gains(1.04f, 1.0f, 0.93f), contrast(1.08f), offsets(6f, 3f, 0f))
            LookFilter.FADE -> chain(saturation(0.85f), contrast(0.82f, 10f))
            LookFilter.PASTEL -> chain(saturation(0.7f), contrast(0.88f, 14f), offsets(4f, 0f, 6f))
            LookFilter.DRAMATIC -> chain(saturation(0.8f), contrast(1.3f, -6f))
            LookFilter.MONO -> saturation(0f)
            LookFilter.NOIR -> chain(saturation(0f), contrast(1.35f, -4f))
            LookFilter.SEPIA -> floatArrayOf(SEPIA_R, SEPIA_RG, SEPIA_RB, 0f, 0f, SEPIA_G, SEPIA_GG, SEPIA_GB, 0f, 0f,
                SEPIA_B, SEPIA_BG, SEPIA_BB, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        }
    }
}

/** Darkens (positive) or lightens (negative) the edges, by distance from the centre: 0 there, 1 in the corners. */
object Vignette {
    private const val INNER = 0.35f
    private const val OUTER = 1.05f
    private const val STRENGTH = 0.85f
    private const val SMOOTH = 3f

    /** How much of black (or white, for a negative vignette) covers a pixel at [distance]. */
    fun alpha(amount: Int, distance: Float): Float {
        val t = ((distance - INNER) / (OUTER - INNER)).coerceIn(0f, 1f)
        return abs(amount) / SLIDER_LIMIT.toFloat() * STRENGTH * t * t * (SMOOTH - 2 * t)
    }

    fun apply(argb: Int, amount: Int, distance: Float): Int {
        val a = alpha(amount, distance)
        if (a == 0f) return argb
        fun channel(shift: Int): Int {
            val c = argb shr shift and MAX_CHANNEL
            val v = if (amount > 0) c * (1 - a) else c + (MAX_CHANNEL - c) * a
            return v.roundToInt().coerceIn(0, MAX_CHANNEL) shl shift
        }
        return (argb and ALPHA_MASK) or channel(RED) or channel(GREEN) or channel(0)
    }

    /** The normalised distance used by [alpha], for pixel centre [x], [y] of a [width]×[height] image. */
    fun distance(x: Int, y: Int, width: Int, height: Int): Float {
        val nx = 2 * ((x + HALF) / width - HALF)
        val ny = 2 * ((y + HALF) / height - HALF)
        return sqrt((nx * nx + ny * ny) / 2)
    }
}

/** Luminance and saturation statistics for auto-enhance. */
class ImageStats(val lumaHistogram: IntArray, val meanSaturation: Float) {
    companion object {
        private const val LUMA_R = 0.2126f
        private const val LUMA_G = 0.7152f
        private const val LUMA_B = 0.0722f

        fun of(pixels: IntArray, stride: Int = 1): ImageStats {
            val histogram = IntArray(LEVELS)
            var saturation = 0.0
            var count = 0
            for (index in pixels.indices step stride.coerceAtLeast(1)) {
                val p = pixels[index]
                val r = p shr RED and MAX_CHANNEL
                val g = p shr GREEN and MAX_CHANNEL
                val b = p and MAX_CHANNEL
                histogram[(LUMA_R * r + LUMA_G * g + LUMA_B * b).roundToInt().coerceIn(0, MAX_CHANNEL)]++
                val max = maxOf(r, g, b)
                if (max > 0) saturation += (max - minOf(r, g, b)) / max.toDouble()
                count++
            }
            return ImageStats(histogram, if (count == 0) 0f else (saturation / count).toFloat())
        }
    }
}

/**
 * Renders a recipe row by row, so a full-resolution photo never needs a second full-size pixel array. Sharpening reads
 * the rows above and below; the vignette is laid over the whole [width]×[height] frame.
 */
class RecipeRenderer(recipe: EditRecipe, private val width: Int, private val height: Int, tone: Boolean = true, color: Boolean = true) {
    private val lut = if (tone) ToneCurve.lut(recipe.adjustments) else null
    private val sharpen = if (tone) recipe.adjustments.sharpness / SLIDER_LIMIT.toFloat() * SHARPEN_MAX else 0f
    private val matrix = if (color) ColorMatrices.recipe(recipe).takeUnless(ColorMatrices::isIdentity) else null
    private val vignette = if (color) recipe.adjustments.vignette else 0
    private val columnDistance = FloatArray(width) { val n = 2 * ((it + HALF) / width - HALF); n * n }

    val changesPixels: Boolean get() = lut != null || sharpen > 0f || matrix != null || vignette != 0

    /** Reads every row with [read], writes the result with [write], and calls [checkpoint] with progress now and then. */
    fun render(read: (y: Int, row: IntArray) -> Unit, write: (y: Int, row: IntArray) -> Unit, checkpoint: (Float) -> Unit = {}) {
        if (width <= 0 || height <= 0) return
        var above = IntArray(width)
        var row = IntArray(width)
        var below = IntArray(width)
        val out = IntArray(width)
        load(read, 0, row)
        row.copyInto(above)
        if (height > 1) load(read, 1, below) else row.copyInto(below)
        for (y in 0 until height) {
            if (sharpen > 0f) sharpenRow(above, row, below, out) else row.copyInto(out)
            finishRow(y, out)
            write(y, out)
            val spare = above
            above = row; row = below; below = spare
            if (y + 2 < height) load(read, y + 2, below) else row.copyInto(below)
            if (y % CHECKPOINT_ROWS == 0) checkpoint(y / height.toFloat())
        }
        checkpoint(1f)
    }

    private fun load(read: (Int, IntArray) -> Unit, y: Int, into: IntArray) {
        read(y, into)
        val curve = lut ?: return
        for (x in into.indices) {
            val p = into[x]
            into[x] = (p and ALPHA_MASK) or (curve[p shr RED and MAX_CHANNEL] shl RED) or
                (curve[p shr GREEN and MAX_CHANNEL] shl GREEN) or curve[p and MAX_CHANNEL]
        }
    }

    /** Unsharp mask with a 3×3 box blur: the pixel plus [sharpen] times its difference from its neighbourhood. */
    private fun sharpenRow(above: IntArray, row: IntArray, below: IntArray, out: IntArray) {
        for (x in 0 until width) {
            val l = (x - 1).coerceAtLeast(0)
            val r = (x + 1).coerceAtMost(width - 1)
            fun channel(shift: Int): Int {
                fun at(source: IntArray) = (source[l] shr shift and MAX_CHANNEL) + (source[x] shr shift and MAX_CHANNEL) +
                    (source[r] shr shift and MAX_CHANNEL)
                val c = row[x] shr shift and MAX_CHANNEL
                val v = c + sharpen * (c - (at(above) + at(row) + at(below)) / NEIGHBOURHOOD)
                return v.roundToInt().coerceIn(0, MAX_CHANNEL) shl shift
            }
            out[x] = (row[x] and ALPHA_MASK) or channel(RED) or channel(GREEN) or channel(0)
        }
    }

    private fun finishRow(y: Int, out: IntArray) {
        val m = matrix
        if (m != null) for (x in out.indices) out[x] = ColorMatrices.apply(m, out[x])
        if (vignette != 0) {
            val ny = 2 * ((y + HALF) / height - HALF)
            val rowDistance = ny * ny
            for (x in out.indices) out[x] = Vignette.apply(out[x], vignette, sqrt((columnDistance[x] + rowDistance) / 2))
        }
    }

    private companion object {
        const val SHARPEN_MAX = 1.5f
        const val NEIGHBOURHOOD = 9f
        const val CHECKPOINT_ROWS = 64
    }
}

/** Renders a whole in-memory image; the preview uses it on its downscaled bitmap. */
fun RecipeRenderer.renderPixels(source: IntArray, target: IntArray, width: Int) =
    render(read = { y, row -> source.copyInto(row, 0, y * width, y * width + width) }, write = { y, row -> row.copyInto(target, y * width) })
