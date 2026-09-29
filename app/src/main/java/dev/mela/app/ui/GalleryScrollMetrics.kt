package dev.mela.app.ui

import kotlin.math.roundToInt

/** Pixel positions follow grid rows, including full-span headers, rather than individual photo indexes. */
internal class GalleryScrollMetrics(
    totalItems: Int,
    columns: Int,
    tileHeight: Float,
    spacing: Float,
    fullSpans: Map<Int, Float>,
    viewportHeight: Int,
    bottomPadding: Int,
) {
    data class Row(val first: Int, val last: Int, val top: Float)
    val rows = buildList {
        var index = 0
        var top = 0f
        while (index < totalItems) {
            val first = index
            val spanHeight = fullSpans[index]
            if (spanHeight != null) index++
            else {
                index++
                while (index < totalItems && index - first < columns.coerceAtLeast(1) && index !in fullSpans) index++
            }
            add(Row(first, index - 1, top))
            top += (spanHeight ?: tileHeight).coerceAtLeast(0f) + spacing
        }
    }
    private val contentHeight = rows.lastOrNull()?.let { it.top + (fullSpans[it.first] ?: tileHeight) } ?: 0f
    val maxScroll = (contentHeight + bottomPadding - viewportHeight).coerceAtLeast(0f)
    fun topOf(index: Int): Float {
        val match = rows.binarySearch { if (index < it.first) 1 else if (index > it.last) -1 else 0 }
        return rows.getOrNull(match)?.top ?: 0f
    }
    fun position(index: Int, offset: Int) = (topOf(index) + offset).coerceIn(0f, maxScroll)
    fun target(fraction: Float): Pair<Int, Int> {
        val pixel = fraction.coerceIn(0f, 1f) * maxScroll
        val found = rows.binarySearch { it.top.compareTo(pixel) }
        val row = rows.getOrNull(if (found >= 0) found else (-found - 2).coerceAtLeast(0)) ?: return 0 to 0
        return row.first to (pixel - row.top).roundToInt()
    }
}
