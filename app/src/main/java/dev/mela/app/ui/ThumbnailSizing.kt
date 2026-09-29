package dev.mela.app.ui

internal fun thumbnailSizeBucket(longestSidePx: Int): Int = when {
    longestSidePx <= 0 -> 0
    longestSidePx <= 256 -> 256
    longestSidePx <= 512 -> 512
    longestSidePx <= 1_024 -> 1_024
    else -> 2_048
}

internal fun thumbnailSampleSize(
    width: Int,
    height: Int,
    targetSidePx: Int,
): Int {
    if (width <= 0 || height <= 0 || targetSidePx <= 0) return 1
    val shortestSide = minOf(width, height)
    var sampleSize = 1
    while (shortestSide / (sampleSize * 2) >= targetSidePx) {
        sampleSize *= 2
    }
    return sampleSize
}
