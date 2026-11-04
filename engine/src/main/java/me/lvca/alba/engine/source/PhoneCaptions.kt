package me.lvca.alba.engine.source

/**
 * The caption a photo carries in its own metadata, as the user would recognise it: XMP `dc:description` first, then EXIF
 * ImageDescription. Cameras fill ImageDescription with their own name or a placeholder ("SONY DSC", "default",
 * "OLYMPUS DIGITAL CAMERA"); those, file-name-like text and text without a letter or digit are not captions.
 */
object PhoneCaptions {
    /** As much caption text as Alba reads back from iCloud. */
    const val MAX_LENGTH = 2_000

    fun pick(xmp: String?, imageDescription: String?): String? = clean(xmpDescription(xmp)) ?: clean(imageDescription)

    /** [raw] as a caption, or null when it is empty or camera boilerplate. */
    fun clean(raw: String?): String? {
        val text = raw?.replace("\u0000", "")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (isBoilerplate(text)) null else text.take(MAX_LENGTH)
    }

    fun isBoilerplate(text: String): Boolean {
        val normalized = text.lowercase().replace(WHITESPACE, " ").trim()
        return normalized in BOILERPLATE || normalized.none(Char::isLetterOrDigit) || FILE_NAME.matches(normalized)
    }

    /** The text of the first `dc:description` in [xmp], the x-default alternative when there are several. */
    fun xmpDescription(xmp: String?): String? {
        if (xmp.isNullOrBlank()) return null
        DESCRIPTION_ATTRIBUTE.find(xmp)?.let { return unescape(it.groupValues[1]) }
        val element = DESCRIPTION_ELEMENT.find(xmp)?.groupValues?.get(1) ?: return null
        val items = LIST_ITEM.findAll(element).map { it.groupValues[1] to it.groupValues[2] }.toList()
        val text = items.firstOrNull { "x-default" in it.first }?.second ?: items.firstOrNull()?.second ?: element
        return unescape(text.replace(TAG, ""))
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
    private val WHITESPACE = Regex("\\s+")
    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")
    private val DESCRIPTION_ATTRIBUTE = Regex("dc:description\\s*=\\s*\"([^\"]*)\"")
    private val DESCRIPTION_ELEMENT = Regex("<dc:description[^>]*>(.*?)</dc:description>", RegexOption.DOT_MATCHES_ALL)
    private val LIST_ITEM = Regex("<rdf:li([^>]*)>(.*?)</rdf:li>", RegexOption.DOT_MATCHES_ALL)

    /** Names cameras and phones give their own files: IMG_1234, DSC01234, PXL_20240101_123456789.jpg. */
    private val FILE_NAME = Regex("(img|dsc|dscn|dscf|dcp|cimg|imag|mvimg|pxl|sam|vid|p)[ _-]?\\d[\\w-]*(\\.[a-z0-9]{3,4})?")

    /** Placeholders cameras and apps write into ImageDescription, lower-cased with single spaces. */
    private val BOILERPLATE = setOf(
        "default", "sony dsc", "olympus digital camera", "kodak digital still camera", "digital camera",
        "minolta digital camera", "konica minolta digital camera", "pentax digital camera", "pentax image",
        "lg digital camera", "samsung", "samsung camera pictures", "exif_jpeg_picture", "exif jpeg picture",
        "image", "picture", "photo", "untitled", "no title", "title", "description", "dcim", "camera", "hdr",
        "jpeg", "jpg", "screenshot", "user comments", "unknown", "none", "null",
    )
}
