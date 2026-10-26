package me.lvca.alba.app.editing

/** The editor's sliders, in Google Photos' order. Values are whole steps; most run from -100 to 100 around 0. */
enum class Adjustment(val min: Int, val max: Int = SLIDER_LIMIT) {
    BRIGHTNESS(-SLIDER_LIMIT),
    CONTRAST(-SLIDER_LIMIT),
    HIGHLIGHTS(-SLIDER_LIMIT),
    SHADOWS(-SLIDER_LIMIT),
    SATURATION(-SLIDER_LIMIT),
    WARMTH(-SLIDER_LIMIT),
    TINT(-SLIDER_LIMIT),
    VIGNETTE(-SLIDER_LIMIT),
    SHARPNESS(0),
}

const val SLIDER_LIMIT = 100

/** Slider positions. Parameters, not pixels, so the editor can save them across process death and in its history. */
data class Adjustments(
    val brightness: Int = 0,
    val contrast: Int = 0,
    val highlights: Int = 0,
    val shadows: Int = 0,
    val saturation: Int = 0,
    val warmth: Int = 0,
    val tint: Int = 0,
    val vignette: Int = 0,
    val sharpness: Int = 0,
) {
    operator fun get(adjustment: Adjustment): Int = when (adjustment) {
        Adjustment.BRIGHTNESS -> brightness
        Adjustment.CONTRAST -> contrast
        Adjustment.HIGHLIGHTS -> highlights
        Adjustment.SHADOWS -> shadows
        Adjustment.SATURATION -> saturation
        Adjustment.WARMTH -> warmth
        Adjustment.TINT -> tint
        Adjustment.VIGNETTE -> vignette
        Adjustment.SHARPNESS -> sharpness
    }

    /** A copy with [adjustment] at [value], kept inside the slider's range. */
    fun with(adjustment: Adjustment, value: Int): Adjustments {
        val v = value.coerceIn(adjustment.min, adjustment.max)
        return when (adjustment) {
            Adjustment.BRIGHTNESS -> copy(brightness = v)
            Adjustment.CONTRAST -> copy(contrast = v)
            Adjustment.HIGHLIGHTS -> copy(highlights = v)
            Adjustment.SHADOWS -> copy(shadows = v)
            Adjustment.SATURATION -> copy(saturation = v)
            Adjustment.WARMTH -> copy(warmth = v)
            Adjustment.TINT -> copy(tint = v)
            Adjustment.VIGNETTE -> copy(vignette = v)
            Adjustment.SHARPNESS -> copy(sharpness = v)
        }
    }

    fun toList(): List<Int> = Adjustment.entries.map(::get)

    /** Whether the tone pass (curve and sharpening) changes pixels. The colour matrix and vignette are separate passes. */
    val changesTone: Boolean get() = brightness != 0 || contrast != 0 || highlights != 0 || shadows != 0 || sharpness != 0

    companion object {
        fun of(values: List<Int>): Adjustments =
            Adjustment.entries.foldIndexed(Adjustments()) { index, result, adjustment -> result.with(adjustment, values.getOrElse(index) { 0 }) }
    }
}

/** Colour looks, applied after the adjustments. [NONE] is the "Original" thumbnail. */
enum class LookFilter { NONE, VIVID, WARM, COOL, FILM, FADE, PASTEL, DRAMATIC, MONO, NOIR, SEPIA }

const val FULL_STRENGTH = 100

/** Everything the editor does to colour, as parameters. Crop and rotation are kept beside it in the edit step. */
data class EditRecipe(
    val adjustments: Adjustments = Adjustments(),
    val filter: LookFilter = LookFilter.NONE,
    val filterStrength: Int = FULL_STRENGTH,
) {
    val isIdentity: Boolean get() = adjustments == Adjustments() && (filter == LookFilter.NONE || filterStrength == 0)
}
