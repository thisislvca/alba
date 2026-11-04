package me.lvca.alba.engine.source

/** Big- or little-endian reads from a file's first bytes; anything past the end reads as -1. */
internal class ByteWindow(private val bytes: ByteArray, private val littleEndian: Boolean = false) {
    val size get() = bytes.size

    fun u8(at: Int): Int = if (at in bytes.indices) bytes[at].toInt() and BYTE_MASK else -1

    fun u16(at: Int): Int {
        if (at < 0 || at + SHORT_BYTES > bytes.size) return -1
        val (high, low) = if (littleEndian) u8(at + 1) to u8(at) else u8(at) to u8(at + 1)
        return (high shl BITS) or low
    }

    fun u32(at: Int): Long {
        if (at < 0 || at + INT_BYTES > bytes.size) return -1
        val (high, low) = if (littleEndian) u16(at + SHORT_BYTES) to u16(at) else u16(at) to u16(at + SHORT_BYTES)
        return (high.toLong() shl SHORT_BITS) or low.toLong()
    }

    fun text(at: Int, length: Int): String =
        if (at >= 0 && at + length <= bytes.size) String(bytes, at, length, Charsets.ISO_8859_1) else ""

    fun startsWith(signature: String): Boolean = text(0, signature.length) == signature

    private companion object {
        const val BYTE_MASK = 0xff
        const val BITS = 8
        const val SHORT_BITS = 16
        const val SHORT_BYTES = 2
        const val INT_BYTES = 4
    }
}

/**
 * Recognises originals iCloud.com's library uploader does not take, from their first bytes, so each is skipped with
 * its own reason instead of a generic one.
 */
internal object MediaSignatures {
    private val GIF = listOf("GIF87a", "GIF89a")
    private val TIFF = listOf("II*\u0000", "MM\u0000*")

    /** Fujifilm RAF, Olympus ORF and Panasonic RW2, which some phones import from cameras. */
    private val OTHER_RAW = listOf("FUJIFILMCCD-RAW", "IIRO", "IIRS", "IIU\u0000")

    /** Matroska and WebM share the EBML magic. */
    private const val EBML = "\u001aEß£"
    private const val RIFF = "RIFF"
    private const val AVI = "AVI "
    private const val RIFF_FORM_AT = 8
    private const val BRAND_LENGTH = 4

    /** Canon CR3 is an ISO media file with its own major brand. */
    private const val CR3_BRAND = "crx "

    /**
     * The MPEG-4 brands Alba has always uploaded as MP4. Fragmented and CMAF brands (`iso3` and later) are left out
     * until it is confirmed that iCloud keeps such an original.
     */
    val MP4_BRANDS = setOf("isom", "iso2", "mp41", "mp42", "avc1")

    /**
     * 3GP, 3G2 and M4V are MPEG-4 relatives that iCloud.com's library uploader does not list. Android often records an
     * `.mp4` under a `3gp4` major brand with `isom` among its compatible ones, so a file is refused for these only when
     * it names no [MP4_BRANDS] at all.
     */
    private val OTHER_VIDEO_BRANDS = listOf("3gp", "3g2", "3gs", "M4V ")

    /** The reason [prefix] is refused, or null when its signature is not one of these. */
    fun refusal(prefix: ByteArray): String? {
        val window = ByteWindow(prefix)
        return when {
            GIF.any(window::startsWith) -> UnsupportedUploadException.GIF
            TIFF.any(window::startsWith) ->
                if (TiffHeader.isRaw(prefix)) UnsupportedUploadException.RAW else UnsupportedUploadException.TIFF
            OTHER_RAW.any(window::startsWith) -> UnsupportedUploadException.RAW
            window.startsWith(EBML) -> UnsupportedUploadException.VIDEO_CONTAINER
            window.startsWith(RIFF) && window.text(RIFF_FORM_AT, BRAND_LENGTH) == AVI -> UnsupportedUploadException.VIDEO_CONTAINER
            else -> null
        }
    }

    /** The reason an ISO media file with [major] and compatible [brands] is refused, or null when it may be uploadable. */
    fun brandRefusal(major: String, brands: List<String>): String? = when {
        major == CR3_BRAND -> UnsupportedUploadException.RAW
        OTHER_VIDEO_BRANDS.any(major::startsWith) && brands.none { it in MP4_BRANDS } -> UnsupportedUploadException.VIDEO_CONTAINER
        brands.any { it in setOf("avif", "avis") } -> UnsupportedUploadException.AVIF
        brands.any { it in setOf("msf1", "hevc", "hevx") } -> UnsupportedUploadException.IMAGE_SEQUENCE
        else -> null
    }
}

/** Reads just enough of a TIFF header to tell a camera RAW (DNG, CR2, NEF, ARW…) from a plain TIFF image. */
internal object TiffHeader {
    private const val DNG_VERSION_TAG = 0xc612
    private const val MAKE_TAG = 0x010f
    private const val SUB_IFDS_TAG = 0x014a
    private const val IFD_OFFSET_AT = 4
    private const val FIRST_IFD_AT = 8
    private const val ENTRY_SIZE = 12
    private const val COUNT_SIZE = 2

    /** Canon CR2 writes "CR" right after the TIFF header. */
    private const val CR2_MARKER = "CR"

    fun isRaw(prefix: ByteArray): Boolean {
        val window = ByteWindow(prefix, littleEndian = prefix.firstOrNull() == 'I'.code.toByte())
        if (window.text(FIRST_IFD_AT, CR2_MARKER.length) == CR2_MARKER) return true
        val ifd = window.u32(IFD_OFFSET_AT).takeIf { it >= FIRST_IFD_AT && it < window.size }?.toInt() ?: return false
        val tags = (0 until window.u16(ifd).coerceAtLeast(0))
            .map { window.u16(ifd + COUNT_SIZE + it * ENTRY_SIZE) }
            .takeWhile { it >= 0 }
            .toSet()
        // A TIFF from a scanner or an editor has no camera make; NEF, ARW and other TIFF-based RAWs do, and keep
        // their raw data in sub-IFDs. Without a DNG version, a camera make with sub-IFDs is read as RAW.
        return DNG_VERSION_TAG in tags || (MAKE_TAG in tags && SUB_IFDS_TAG in tags)
    }
}

/** The item types (`hvc1`, `av01`, `grid`, `Exif`…) a HEIF file's `meta`/`iinf` box declares, from its first bytes. */
internal object HeifItems {
    private const val BOX_HEADER = 8
    private const val FULL_BOX_HEADER = 12
    private const val TYPE_LENGTH = 4
    private const val SHORT_FIELD = 2
    private const val LONG_FIELD = 4

    /** `infe` version 2 has a 16-bit item id, version 3 a 32-bit one; both are followed by a protection index. */
    private const val FIRST_TYPED_INFE_VERSION = 2
    private const val LONG_ID_INFE_VERSION = 3

    fun types(prefix: ByteArray, afterFtyp: Int): Set<String> {
        val window = ByteWindow(prefix)
        val meta = window.box("meta", afterFtyp, prefix.size) ?: return emptySet()
        val iinf = window.box("iinf", meta.first + FULL_BOX_HEADER, meta.last + 1) ?: return emptySet()
        val countSize = if (window.u8(iinf.first + BOX_HEADER) == 0) SHORT_FIELD else LONG_FIELD
        return window.children(iinf.first + FULL_BOX_HEADER + countSize, iinf.last + 1)
            .filter { window.text(it + LONG_FIELD, TYPE_LENGTH) == "infe" }
            .mapNotNull { window.itemType(it) }
            .toSet()
    }

    private fun ByteWindow.itemType(infe: Int): String? {
        val version = u8(infe + BOX_HEADER).takeIf { it >= FIRST_TYPED_INFE_VERSION } ?: return null
        val idSize = if (version >= LONG_ID_INFE_VERSION) LONG_FIELD else SHORT_FIELD
        return text(infe + FULL_BOX_HEADER + idSize + SHORT_FIELD, TYPE_LENGTH).takeIf { it.isNotEmpty() }
    }

    /** Start offsets of the boxes laid end to end from [from] to [to], stopping at the first unreadable size. */
    private fun ByteWindow.children(from: Int, to: Int): List<Int> = generateSequence(from) { at ->
        val size = u32(at)
        (at + size).takeIf { size >= BOX_HEADER && at + size + BOX_HEADER <= to }?.toInt()
    }.takeWhile { it + BOX_HEADER <= to && u32(it) >= BOX_HEADER }.toList()

    /** The bytes of the first box named [name] among the boxes from [from] to [to]. */
    private fun ByteWindow.box(name: String, from: Int, to: Int): IntRange? = children(from, to)
        .firstOrNull { text(it + LONG_FIELD, TYPE_LENGTH) == name }
        ?.let { it until minOf((it + u32(it)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), size) }
}
