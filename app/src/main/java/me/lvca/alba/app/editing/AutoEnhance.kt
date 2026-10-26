package me.lvca.alba.app.editing

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * One-tap enhance: simple auto levels from the luminance histogram. It moves the median part of the way to mid-grey,
 * stretches a narrow range, opens crushed shadows, recovers blown highlights and lifts dull colour. A well-exposed
 * photo with a full range gets nothing.
 */
object AutoEnhance {
    private const val CLIP = 0.01f
    private const val MEDIAN_PULL = 0.6f
    private const val MEDIAN_SAFE_MIN = 0.02f
    private const val MEDIAN_SAFE_MAX = 0.98f
    private const val TARGET_SPREAD = 0.9f
    private const val MAX_BRIGHTNESS = 0.5f
    private const val MAX_CONTRAST = 0.5f
    private const val DARK_LEVEL = 0.12f
    private const val BRIGHT_LEVEL = 0.92f
    private const val DARK_ALLOWED = 0.15f
    private const val BRIGHT_ALLOWED = 0.1f
    private const val RECOVERY_GAIN = 1.5f
    private const val MAX_RECOVERY = 0.4f
    private const val DULL_SATURATION = 0.4f
    private const val GREY_SATURATION = 0.03f
    private const val MAX_SATURATION = 0.3f
    private const val TONE_RANGE = 0.8f
    private const val HALF = 0.5f
    private const val MIN_CHANGE = 0.05f

    fun adjustments(stats: ImageStats): Adjustments {
        val histogram = stats.lumaHistogram
        val total = histogram.sum()
        if (total == 0) return Adjustments()
        fun percentile(fraction: Float): Float {
            val target = fraction * total
            var seen = 0
            histogram.forEachIndexed { level, count -> seen += count; if (seen >= target) return level / MAX_CHANNEL.toFloat() }
            return 1f
        }
        fun share(predicate: (Float) -> Boolean) =
            histogram.withIndex().filter { predicate(it.index / MAX_CHANNEL.toFloat()) }.sumOf { it.value } / total.toFloat()

        val median = percentile(HALF).coerceIn(MEDIAN_SAFE_MIN, MEDIAN_SAFE_MAX)
        val target = median + (HALF - median) * MEDIAN_PULL
        val gamma = ln(target) / ln(median)
        val brightness = (-log2(gamma) / TONE_RANGE).coerceIn(-MAX_BRIGHTNESS, MAX_BRIGHTNESS)

        // Stretch the range after brightness towards the target, but never so far that either end clips.
        val appliedGamma = 2f.pow(-brightness * TONE_RANGE)
        val low = percentile(CLIP).pow(appliedGamma)
        val high = percentile(1 - CLIP).pow(appliedGamma)
        val slope = minOf(TARGET_SPREAD / (high - low).coerceAtLeast(MEDIAN_SAFE_MIN), HALF / maxOf(HALF - low, high - HALF, MEDIAN_SAFE_MIN))
        val contrast = (log2(slope) / TONE_RANGE).coerceIn(0f, MAX_CONTRAST)

        val shadows = ((share { it < DARK_LEVEL } - DARK_ALLOWED) * RECOVERY_GAIN).coerceIn(0f, MAX_RECOVERY)
        val highlights = -((share { it > BRIGHT_LEVEL } - BRIGHT_ALLOWED) * RECOVERY_GAIN).coerceIn(0f, MAX_RECOVERY)
        val saturation = if (stats.meanSaturation < GREY_SATURATION) 0f else (DULL_SATURATION - stats.meanSaturation).coerceIn(0f, MAX_SATURATION)

        // Changes too small to see are left out, so a balanced photo gets nothing.
        fun steps(value: Float) = if (abs(value) < MIN_CHANGE) 0 else (value * SLIDER_LIMIT).roundToInt()
        return Adjustments(brightness = steps(brightness), contrast = steps(contrast), highlights = steps(highlights),
            shadows = steps(shadows), saturation = steps(saturation))
    }

    /** Enhance replaces only the sliders it sets, so the user's warmth, tint, vignette and sharpness stay. */
    fun applyTo(current: Adjustments, enhanced: Adjustments): Adjustments = current.copy(brightness = enhanced.brightness,
        contrast = enhanced.contrast, highlights = enhanced.highlights, shadows = enhanced.shadows, saturation = enhanced.saturation)

    fun isApplied(current: Adjustments, enhanced: Adjustments): Boolean =
        enhanced != Adjustments() && applyTo(current, enhanced) == current

    fun remove(current: Adjustments): Adjustments = applyTo(current, Adjustments())
}
