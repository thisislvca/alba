package me.lvca.alba.app.ui.viewer

import androidx.exifinterface.media.ExifInterface
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The camera facts Photos shows under a photo's date, as the file's EXIF records them. Every field is optional:
 * screenshots, edited exports and many Android photos carry only some of them.
 */
internal data class CameraExif(
    val make: String? = null,
    val model: String? = null,
    val lensModel: String? = null,
    val fNumber: Double? = null,
    val exposureSeconds: Double? = null,
    val iso: Int? = null,
    val focalLengthMm: Double? = null,
    /** EXIF FocalLengthIn35mmFilm: what Apple shows as the lens's focal length ("24 mm" for a 6.765 mm main camera). */
    val focalLength35mm: Int? = null,
) {
    companion object {
        fun from(exif: ExifInterface) = CameraExif(
            make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf(String::isNotEmpty),
            model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf(String::isNotEmpty),
            lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.takeIf(String::isNotEmpty),
            fNumber = exif.positiveDouble(ExifInterface.TAG_F_NUMBER),
            exposureSeconds = exif.positiveDouble(ExifInterface.TAG_EXPOSURE_TIME),
            iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 },
            focalLengthMm = exif.positiveDouble(ExifInterface.TAG_FOCAL_LENGTH),
            focalLength35mm = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0).takeIf { it > 0 },
        )

        private fun ExifInterface.positiveDouble(tag: String): Double? =
            getAttributeDouble(tag, Double.NaN).takeIf { it.isFinite() && it > 0.0 }
    }
}

/** Which of an iPhone's cameras took the photo, named as Apple names them in Photos' Info. */
internal enum class LensRole { MAIN, ULTRA_WIDE, TELEPHOTO, FRONT }

/** Formats [CameraExif] the way Apple Photos and iCloud.com do. */
internal object CameraFormat {
    /** Below this 35 mm equivalent an iPhone's back camera is the Ultra Wide; above [MAIN_MAX_35MM], the Telephoto. */
    private const val ULTRA_WIDE_MAX_35MM = 20
    private const val MAIN_MAX_35MM = 50

    /** Exposures this short or shorter are always a fraction of a second, like "1/8000 s". */
    private const val ALWAYS_FRACTION_SECONDS = 0.25

    /** How close 1/t must be to a whole number for a longer exposure to read as a fraction ("1/2 s"), not "0.3 s". */
    private const val FRACTION_TOLERANCE = 0.05

    /** "iPhone 15 Pro" rather than "Apple iPhone 15 Pro", and "Canon EOS R5" rather than "Canon Canon EOS R5". */
    fun device(exif: CameraExif): String? {
        val model = exif.model
        val make = exif.make
        return when {
            model == null -> make
            make == null || make.equals("Apple", ignoreCase = true) || model.startsWith(make, ignoreCase = true) -> model
            // Some makers write their company name in full ("NIKON CORPORATION") and the model alone ("NIKON Z 6").
            model.startsWith(make.substringBefore(' '), ignoreCase = true) -> model
            else -> "$make $model"
        }
    }

    /** The f-number to two decimals, as Apple rounds it: 1.7799999713880652 is 1.78, 2.8 stays 2.8 and 16.0 is 16. */
    fun fNumber(value: Double, locale: Locale): String = decimal(value, 2, locale)

    /** "f/1.78". */
    fun aperture(value: Double, locale: Locale): String = "f/" + fNumber(value, locale)

    /**
     * The exposure time as Apple shows it: a fraction of a second for short exposures ("1/75 s", "1/8000 s") and
     * seconds for long ones ("1 s", "2.5 s", "30 s").
     */
    fun shutter(seconds: Double, locale: Locale): String {
        val denominator = 1.0 / seconds
        val whole = denominator.roundToLong()
        val fraction = seconds < 1.0 && whole >= 1 &&
            (seconds <= ALWAYS_FRACTION_SECONDS || abs(denominator - whole) < FRACTION_TOLERANCE)
        return if (fraction && whole > 1) "1/$whole s" else "${decimal(seconds, 1, locale)} s"
    }

    /** "ISO 640". */
    fun iso(value: Int): String = "ISO $value"

    /** The 35 mm equivalent when the file records it ("24 mm"), otherwise the lens's real focal length ("6.77 mm"). */
    fun focalLength(exif: CameraExif, locale: Locale): String? =
        exif.focalLength35mm?.let { "$it mm" } ?: exif.focalLengthMm?.let { "${decimal(it, 2, locale)} mm" }

    /**
     * Which of an iPhone or iPad's cameras took the photo, from the lens model Apple writes ("iPhone 15 Pro back triple
     * camera 6.765mm f/1.78") and the 35 mm focal length. Null for other makers' lenses, whose model is shown as it is.
     */
    fun lensRole(exif: CameraExif): LensRole? {
        val lens = exif.lensModel?.takeIf { isAppleLens(exif) } ?: return null
        val lower = lens.lowercase(Locale.ROOT)
        if ("front" in lower) return LensRole.FRONT
        if ("back" !in lower) return null
        val focal = exif.focalLength35mm
        return when {
            focal != null && focal < ULTRA_WIDE_MAX_35MM -> LensRole.ULTRA_WIDE
            focal != null && focal > MAIN_MAX_35MM -> LensRole.TELEPHOTO
            focal != null -> LensRole.MAIN
            // An iPhone with one back camera says so; with several and no 35 mm focal length, it can't be told.
            "dual" in lower || "triple" in lower -> null
            else -> LensRole.MAIN
        }
    }

    /**
     * The lens line under the device, as Apple writes it: "Main Camera — 24 mm ƒ1.78". [roleName] is the localized
     * name of [lensRole]; another maker's lens keeps its own model name. Null when the file says nothing about the lens.
     */
    fun lensLine(exif: CameraExif, roleName: String?, locale: Locale): String? {
        val name = roleName ?: exif.lensModel?.takeIf { lensRole(exif) == null && !isAppleLens(exif) }
        val optics = listOfNotNull(focalLength(exif, locale), exif.fNumber?.let { "ƒ" + fNumber(it, locale) })
            .joinToString(" ").takeIf(String::isNotEmpty)
        return when {
            name != null && optics != null -> "$name — $optics"
            else -> name ?: optics
        }
    }

    /** "ISO 640 · f/1.78 · 1/75 s", in Apple's order, leaving out what the file doesn't record. */
    fun exposure(exif: CameraExif, locale: Locale): String? =
        listOfNotNull(exif.iso?.let(::iso), exif.fNumber?.let { aperture(it, locale) }, exif.exposureSeconds?.let { shutter(it, locale) })
            .joinToString(" · ").takeIf(String::isNotEmpty)

    /** Apple's own lens descriptions repeat the real focal length and aperture, which the lens line already shows better. */
    private fun isAppleLens(exif: CameraExif): Boolean {
        val lens = exif.lensModel ?: return false
        return exif.make.equals("Apple", ignoreCase = true) || lens.startsWith("iPhone") || lens.startsWith("iPad")
    }

    /** [value] rounded half up to at most [digits] decimals, without trailing zeros, in [locale]'s notation. */
    private fun decimal(value: Double, digits: Int, locale: Locale): String {
        // From the shortest decimal form, so 6.765 rounds to 6.77 rather than to its binary neighbour's 6.76.
        val rounded = BigDecimal(value.toString()).setScale(digits, RoundingMode.HALF_UP)
        return NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = digits
            isGroupingUsed = false
        }.format(rounded)
    }
}
