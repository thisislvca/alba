package me.lvca.alba.app.ui.gallery

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

/**
 * Google Photos' hard-stop scrubber: every month gets an equal share of the track, however many photos it holds.
 * [monthTops] are each month's scroll position in pixels, in order; [maxScroll] is the grid's end.
 */
internal class MonthBuckets(monthTops: List<Float>, private val maxScroll: Float) {
    val count = monthTops.size

    // Months that start beyond the end of scrolling all share the last stretch of the track up to 1.
    private val knots = buildList {
        monthTops.forEachIndexed { month, top -> if (top < maxScroll) add(top.coerceAtLeast(0f) to month.toFloat() / count) }
        add(maxScroll to 1f)
    }

    /** The month a scrubber at [fraction] of its track points at. */
    fun monthAt(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * count).toInt().coerceIn(0, (count - 1).coerceAtLeast(0))

    /** Where [month] starts on the track. */
    fun fractionOf(month: Int): Float = if (count == 0) 0f else month.coerceIn(0, count).toFloat() / count

    /** The thumb's place for a scroll [position], moving smoothly through each month's share. */
    fun fractionAt(position: Float): Float {
        if (count == 0 || maxScroll <= 0f) return 0f
        if (position >= maxScroll) return 1f
        val next = knots.indexOfFirst { it.first > position }
        // Above the first month (a leading strip or prompt) the thumb stays at the top.
        if (next <= 0) return 0f
        val (x0, y0) = knots[next - 1]
        val (x1, y1) = knots[next]
        return y0 + (y1 - y0) * ((position - x0) / (x1 - x0)).coerceIn(0f, 1f)
    }
}
