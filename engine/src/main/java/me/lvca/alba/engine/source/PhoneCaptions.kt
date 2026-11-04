package me.lvca.alba.engine.source

/**
 * The caption a photo carries in its own metadata, as the user would recognise it: XMP `dc:description` first, then EXIF
 * ImageDescription. Cameras fill ImageDescription with their own name or a placeholder ("SONY DSC", "default",
 * "OLYMPUS DIGITAL CAMERA"); those, file-name-like text and text without a letter or digit are not captions.
 */
object PhoneCaptions {
    /** As much caption text as Alba reads back from iCloud. */
    const val MAX_LENGTH = 2_000

    fun pick(xmp: String?, imageDescription: String?): String? =
        xmpDescriptions(xmp).firstNotNullOfOrNull(::clean) ?: clean(imageDescription)

    /** [raw] as a caption, or null when it is empty or camera boilerplate. */
    fun clean(raw: String?): String? {
        val text = raw?.replace("\u0000", "")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (isBoilerplate(text)) null else text.take(MAX_LENGTH)
    }

    fun isBoilerplate(text: String): Boolean {
        val normalized = text.lowercase().replace(WHITESPACE, " ").trim()
        return normalized in BOILERPLATE || normalized.none(Char::isLetterOrDigit) || FILE_NAME.matches(normalized)
    }

    /**
     * The texts of the first `dc:description` in [xmp], the x-default alternative first and then the other languages,
     * so an empty or self-closing x-default item falls back to the others.
     */
    fun xmpDescriptions(xmp: String?): List<String> {
        if (xmp.isNullOrBlank()) return emptyList()
        DESCRIPTION_ATTRIBUTE.find(xmp)?.let { return listOf(unescape(it.groupValues[1])) }
        val element = DESCRIPTION_ELEMENT.find(xmp)?.groupValues?.get(1) ?: return emptyList()
        val items = LIST_ITEM.findAll(element).map { it.groupValues[1] to it.groupValues[ITEM_TEXT] }.toList()
        if (items.isEmpty()) return listOf(unescape(element.replace(TAG, "")))
        val (preferred, others) = items.partition { "x-default" in it.first }
        return (preferred + others).map { unescape(it.second.replace(TAG, "")) }
    }

    private fun unescape(text: String): String = ENTITY.replace(text) { match ->
        when (val name = match.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> {
                val code = if (name.startsWith("#x", ignoreCase = true)) name.drop(2).toIntOrNull(HEX) else name.drop(1).toIntOrNull()
                code?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) } ?: match.value
            }
        }
    }

    private const val HEX = 16

    /** The group of [LIST_ITEM] that holds an item's text. */
    private const val ITEM_TEXT = 3
    private val WHITESPACE = Regex("\\s+")
    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")
    private val DESCRIPTION_ATTRIBUTE = Regex("dc:description\\s*=\\s*\"([^\"]*)\"")
    private val DESCRIPTION_ELEMENT = Regex("<dc:description[^>]*>(.*?)</dc:description>", RegexOption.DOT_MATCHES_ALL)

    /** An `rdf:li`, either self-closing or with its text in group 3. */
    private val LIST_ITEM = Regex("<rdf:li\\b([^>]*?)(/>|>(.*?)</rdf:li>)", RegexOption.DOT_MATCHES_ALL)

    /**
     * Names cameras and phones give their own files, and nothing shorter: IMG_1234, IMG_20240101_123456, DSC01234,
     * DSC_1234, DSCN1234, DSCF1234, DCP_1234, CIMG1234, IMAG1234, SAM_1234, GOPR1234, DJI_1234, P1010123 (Panasonic),
     * PXL_20240101_123456789, MVIMG_ and VID_ dates, with an optional suffix and extension. "P1" or "Sam2024" are not.
     */
    private val FILE_NAME = Regex(
        "(img_\\d{4,}|(img|pxl|mvimg|vid)_\\d{8}_\\d{6,9}|dsc\\d{5}|_dsc\\d{4}|dsc_\\d{4}|dscn\\d{4}|dscf\\d{4}|dcp_?\\d{4}|cimg\\d{4}|" +
            "imag\\d{4}|sam_\\d{4}|gopr\\d{4}|dji_\\d{4}|p\\d{7})([_~-][a-z0-9]+)*( \\(\\d+\\))?(\\.[a-z0-9]{2,4}){0,2}",
    )

    /** Placeholders cameras and apps write into ImageDescription, lower-cased with single spaces. */
    private val BOILERPLATE = setOf(
        "default", "sony dsc", "olympus digital camera", "kodak digital still camera", "digital camera",
        "minolta digital camera", "konica minolta digital camera", "pentax digital camera", "pentax image",
        "lg digital camera", "samsung", "samsung camera pictures", "exif_jpeg_picture", "exif jpeg picture",
        "image", "picture", "photo", "untitled", "no title", "title", "description", "dcim", "camera", "hdr",
        "jpeg", "jpg", "screenshot", "user comments", "unknown", "none", "null",
    )
}
