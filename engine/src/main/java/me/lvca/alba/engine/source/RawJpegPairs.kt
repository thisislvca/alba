package me.lvca.alba.engine.source

/**
 * Matches a RAW original to the JPEG the camera saved with it, by file name in the same folder.
 *
 * iCloud.com uploads every file as its own item (one `photosupload` request per file) and its library uploader does not
 * accept RAW at all, so a RAW+JPEG pair cannot be uploaded as one iCloud asset: the JPEG backs up on its own and the
 * RAW is skipped. The pairing only lets the skipped RAW say that its JPEG is backed up.
 *
 * Pixel names a pair `PXL_20240501_101010123.RAW-02.ORIGINAL.dng` and `PXL_20240501_101010123.RAW-01.COVER.jpg` (or
 * `.RAW-01.MP.COVER.jpg` with motion); Samsung's Pro mode and most other camera apps give both the same base name,
 * `20240501_101010.dng` and `20240501_101010.jpg`.
 */
object RawJpegPairs {
    private val PIXEL_RAW_SUFFIX = Regex("\\.RAW-\\d+(\\..*)?$", RegexOption.IGNORE_CASE)
    private val JPEG_EXTENSIONS = setOf("jpg", "jpeg")

    /** The name both files of a pair share, lower case: the base name before Pixel's `.RAW-nn.` part or the extension. */
    fun stem(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringBeforeLast('.')
        return base.replace(PIXEL_RAW_SUFFIX, "").lowercase()
    }

    /** Whether one of [namesInFolder] is the JPEG saved with the RAW named [rawName]. */
    fun hasJpegCompanion(rawName: String, namesInFolder: Collection<String>): Boolean {
        val stem = stem(rawName)
        return stem.isNotEmpty() && namesInFolder.any { name ->
            !name.equals(rawName, ignoreCase = true) &&
                name.substringAfterLast('.', "").lowercase() in JPEG_EXTENSIONS &&
                stem(name) == stem
        }
    }
}
