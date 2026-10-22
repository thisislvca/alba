package me.lvca.alba.app.ui.gallery

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The library's zoom levels, smallest tiles first. They follow Google Photos' phone levels: a compact month view, the
 * default day view, a roomier day view and one photo per row.
 */
internal enum class GridLevel(val dayHeaders: Boolean) {
    COMPACT(dayHeaders = false),
    DAY(dayHeaders = true),
    COZY(dayHeaders = true),
    FIT(dayHeaders = true),
    ;

    /**
     * Columns for a grid [widthDp] wide on a screen whose shortest side is [smallestWidthDp]. Portrait counts come from
     * the shortest side; wider grids (landscape, tablets beside a rail) scale them so tiles keep their size.
     */
    fun columns(smallestWidthDp: Float, widthDp: Float): Int {
        // Google Photos' photogrid_calculator column counts: compact 4 / sw400 5 / sw700 7, default 3 / sw413 4 / sw700 5.
        val large = smallestWidthDp >= LARGE_SCREEN_DP
        val compact = if (large) COMPACT_LARGE else if (smallestWidthDp >= COMPACT_FIVE_FROM_DP) COMPACT_PHONE else COMPACT_SMALL
        val day = if (large) DAY_LARGE else if (smallestWidthDp >= GalleryFeel.DAY_FOUR_COLUMNS_FROM_DP) DAY_PHONE else DAY_SMALL
        val base = when (this) {
            COMPACT -> compact
            DAY -> day
            COZY -> (day - 1).coerceAtLeast(MIN_COZY)
            FIT -> 1
        }
        val wider = smallestWidthDp > 0 && widthDp > smallestWidthDp * LANDSCAPE_RATIO
        return if (wider) (base * widthDp / smallestWidthDp).roundToInt().coerceAtLeast(1) else base
    }

    /** The level one pinch step away: larger tiles when [zoomIn], or null at either end. */
    fun next(zoomIn: Boolean): GridLevel? = entries.getOrNull(ordinal + if (zoomIn) 1 else -1)

    private companion object {
        const val LARGE_SCREEN_DP = 700f
        const val COMPACT_FIVE_FROM_DP = 400f
        const val COMPACT_SMALL = 4
        const val COMPACT_PHONE = 5
        const val COMPACT_LARGE = 7
        const val DAY_SMALL = 3
        const val DAY_PHONE = 4
        const val DAY_LARGE = 5
        const val MIN_COZY = 2

        /** A grid this much wider than the shortest side is in landscape or beside a rail. */
        const val LANDSCAPE_RATIO = 1.05f
    }
}

/** Past the last level the grid's scale bottoms out at 1/6 + 2/3 of its size. */
private const val RUBBER_SHARE = 3f

/** How far a pinch at finger [scale] has morphed toward the next level, 0..1. */
internal fun pinchProgress(scale: Float): Float =
    if (scale >= 1f) ((scale - 1f) / GalleryFeel.PINCH_IN_SPAN).coerceIn(0f, 1f)
    else ((1f - scale) / GalleryFeel.PINCH_OUT_SPAN).coerceIn(0f, 1f)

/** Visual scale while pinching past the last level: resists and bottoms out near 0.83 (or tops out near 1.2). */
internal fun pinchRubberBand(scale: Float): Float {
    fun shrink(s: Float) = minOf(1f, (1f / (2f - s.coerceIn(0f, 1f))) / RUBBER_SHARE + (RUBBER_SHARE - 1f) / RUBBER_SHARE)
    return if (scale <= 1f) shrink(scale) else 1f / shrink(1f / scale)
}

/** Where one item draws during a morph: its new top-left, scale from its top-left, and alpha. */
internal data class ItemPose(val left: Float, val top: Float, val scale: Float, val alpha: Float)

/**
 * The pose of one item at morph [progress]. The anchor tile moves from [anchorFrom] (old layout) to [anchorTo] (new
 * layout); everything is laid out in both layouts at once. An item with a [counterpart] in the other layout travels to
 * it; one without follows the anchor's transform and fades, headers over half the morph so two never overlap.
 */
internal fun morphPose(own: Rect, counterpart: Rect?, anchorFrom: Rect, anchorTo: Rect, progress: Float,
    incoming: Boolean, header: Boolean): ItemPose {
    val p = progress.coerceIn(0f, 1f)
    val ratio = if (anchorFrom.width > 0f) anchorTo.width / anchorFrom.width else 1f
    val scale = lerp(1f, ratio, p)
    val anchorLeft = lerp(anchorFrom.left, anchorTo.left, p)
    val anchorTop = lerp(anchorFrom.top, anchorTo.top, p)
    if (!incoming) {
        if (counterpart != null) {
            val own2 = if (own.width > 0f) counterpart.width / own.width else 1f
            return ItemPose(lerp(own.left, counterpart.left, p), lerp(own.top, counterpart.top, p), lerp(1f, own2, p), 1f)
        }
        return ItemPose((own.left - anchorFrom.left) * scale + anchorLeft, (own.top - anchorFrom.top) * scale + anchorTop, scale,
            if (header) (1f - p / HEADER_SHARE).coerceIn(0f, 1f) else 1f - p)
    }
    // Incoming items with a counterpart are drawn by the outgoing layout.
    if (counterpart != null) return ItemPose(own.left, own.top, 1f, 0f)
    val inverse = if (ratio > 0f) scale / ratio else 1f
    return ItemPose((own.left - anchorTo.left) * inverse + anchorLeft, (own.top - anchorTo.top) * inverse + anchorTop, inverse,
        if (header) ((p - HEADER_SHARE) / HEADER_SHARE).coerceIn(0f, 1f) else p)
}

/** Headers cross-fade over half the morph each, so an outgoing and an incoming header never overlap. */
private const val HEADER_SHARE = .5f

/** The photo nearest [point] by distance between centres, for anchoring a pinch. */
internal fun <K> nearestByCentre(items: List<Pair<K, Rect>>, point: Offset): K? =
    items.minByOrNull { (_, rect) -> hypot(rect.center.x - point.x, rect.center.y - point.y) }?.first

/**
 * The photo under [point], or when it falls between tiles, the nearest one in the nearest row: drag-select follows
 * reading order, so the row decides before the column does.
 */
internal fun <K> nearestInRow(items: List<Pair<K, Rect>>, point: Offset): K? =
    items.minWithOrNull(compareBy<Pair<K, Rect>>({ (_, r) -> distanceOutside(point.y, r.top, r.bottom) },
        { (_, r) -> distanceOutside(point.x, r.left, r.right) }))?.first

private const val EDGE_BIAS = .001f

private fun distanceOutside(value: Float, start: Float, end: Float) = when {
    value < start -> start - value
    // Just past a tile's end edge still counts as outside it, so the next tile wins a tie.
    value >= end -> value - end + EDGE_BIAS
    else -> 0f
}

internal fun LazyGridItemInfo.rect() = Rect(Offset(offset.x.toFloat(), offset.y.toFloat()), Size(size.width.toFloat(), size.height.toFloat()))

/** Grid items that are dated headers fade rather than travel. */
internal fun isDateHeader(key: Any?) = key is String && (key.startsWith("month-") || key.startsWith("day-"))

/**
 * A pinch between grid levels. While the fingers move, the current grid and the next level are both laid out and
 * every item interpolates between them; release commits past [GalleryFeel.PINCH_COMMIT_PROGRESS] and settles in
 * [GalleryFeel.PINCH_SETTLE_MILLIS]. The incoming grid uses its own [incomingGrid] state, positioned so the anchor's
 * row keeps its top; committing copies that position into the real grid in the same frame.
 */
@Stable
internal class GridPinch(private val scope: CoroutineScope) {
    var level = GridLevel.DAY
    var grid: LazyGridState? = null
    var reducedMotion = false
    var mediaKeys: Set<String> = emptySet()
    var indexIn: (String, GridLevel) -> Int = { _, _ -> 0 }
    var commit: (GridLevel) -> Unit = {}

    /** The level being morphed toward, or null when not morphing. */
    var target by mutableStateOf<GridLevel?>(null)
        private set
    var progress by mutableFloatStateOf(0f)
        private set

    /** The whole grid's scale past the last level, around [pivot]. */
    var rubber by mutableFloatStateOf(1f)
        private set
    var pivot = Offset.Zero
        private set

    /** Where the incoming grid starts: the anchor's index in that level, with its row at the same top. */
    var incomingIndex = 0
        private set
    var incomingOffset = 0
        private set
    var incomingGrid: LazyGridState? = null

    private var from: Map<Any, Rect> = emptyMap()
    private var to by mutableStateOf<Map<Any, Rect>>(emptyMap())
    private var anchorKey: String? = null
    private var anchorFrom = Rect.Zero
    private var settling: Job? = null
    val active get() = target != null || rubber != 1f

    /** Starts a pinch around [centroid]; false while the previous one is still settling. */
    fun begin(centroid: Offset): Boolean {
        if (settling?.isActive == true) return false
        // The morph is measured against the grid as it stands, so a running fling must stop first.
        grid?.let { scope.launch { it.stopScroll() } }
        target = null; progress = 0f; rubber = 1f
        val visible = grid?.layoutInfo?.visibleItemsInfo.orEmpty()
        from = visible.associate { it.key to it.rect() }
        val photos = visible.filter { it.key in mediaKeys }.map { it.key as String to it.rect() }
        anchorKey = nearestByCentre(photos, centroid)
        anchorFrom = photos.firstOrNull { it.first == anchorKey }?.second ?: Rect(centroid, Size(1f, 1f))
        pivot = centroid
        return true
    }

    fun update(scale: Float) {
        val next = level.next(zoomIn = scale > 1f)
        val anchor = anchorKey
        if (next == null || anchor == null) {
            target = null; progress = 0f
            rubber = if (anchor == null) 1f else pinchRubberBand(scale)
            return
        }
        rubber = 1f
        if (next != target) {
            incomingIndex = indexIn(anchor, next)
            incomingOffset = -anchorFrom.top.roundToInt()
            to = emptyMap()
            target = next
        }
        progress = pinchProgress(scale)
    }

    /** Records the incoming layout once measured. */
    fun measured(items: List<LazyGridItemInfo>) { to = items.associate { it.key to it.rect() } }

    fun end() {
        settling = scope.launch {
            val spec = if (reducedMotion) snap() else tween<Float>(GalleryFeel.PINCH_SETTLE_MILLIS, easing = LinearOutSlowInEasing)
            val goal = target
            if (goal == null) {
                if (rubber != 1f) animate(rubber, 1f, animationSpec = if (reducedMotion) snap()
                    else tween(GalleryFeel.PINCH_RUBBER_RETURN_MILLIS, easing = LinearOutSlowInEasing)) { value, _ -> rubber = value }
                rubber = 1f
                return@launch
            }
            val commits = progress > GalleryFeel.PINCH_COMMIT_PROGRESS
            animate(progress, if (commits) 1f else 0f, animationSpec = spec) { value, _ -> progress = value }
            if (commits) {
                val incoming = incomingGrid
                if (incoming != null) grid?.requestScrollToItem(incoming.firstVisibleItemIndex, incoming.firstVisibleItemScrollOffset)
                commit(goal)
            }
            target = null; progress = 0f
        }
    }

    /**
     * How an item of the current grid ([incoming] false) or the incoming one draws: its offset from where the grid
     * placed it, its scale and alpha. Null when not morphing.
     */
    fun layer(key: Any, incoming: Boolean): ItemPose? {
        if (target == null) return null
        val targetRects = to
        val own = (if (incoming) targetRects[key] else from[key]) ?: return if (incoming) HIDDEN else null
        val anchorTo = anchorKey?.let(targetRects::get) ?: anchorFrom
        val counterpart = if (incoming) from[key] else targetRects[key]
        val pose = morphPose(own, counterpart, anchorFrom, anchorTo, progress, incoming, isDateHeader(key))
        return pose.copy(left = pose.left - own.left, top = pose.top - own.top)
    }

    private companion object {
        /** The incoming grid stays invisible until it has been measured. */
        val HIDDEN = ItemPose(0f, 0f, 1f, 0f)
    }
}

/** Draws one grid item at its pinch [GridPinch.layer]; reads the morph only while drawing, so pinching never recomposes. */
internal fun Modifier.pinchPose(pinch: GridPinch, key: Any, incoming: Boolean): Modifier = graphicsLayer {
    val pose = pinch.layer(key, incoming) ?: return@graphicsLayer
    transformOrigin = TransformOrigin(0f, 0f)
    translationX = pose.left
    translationY = pose.top
    scaleX = pose.scale
    scaleY = pose.scale
    alpha = pose.alpha
}
