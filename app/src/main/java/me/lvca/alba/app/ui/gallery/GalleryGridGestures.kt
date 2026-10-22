package me.lvca.alba.app.ui.gallery

import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.abs

/** Selection uses a fixed starting snapshot so dragging back never leaves stray selected items. */
internal fun selectionRange(ids: List<String>, anchor: String, end: String): Set<String> {
    val a = ids.indexOf(anchor); val b = ids.indexOf(end)
    return if (a < 0 || b < 0) emptySet() else ids.subList(minOf(a, b), maxOf(a, b) + 1).toSet()
}

/**
 * Drag-select autoscroll speed for a finger at [y], in pixels per second: zero between the edge bands, ramping
 * linearly to [maxSpeed] at [edge] deep into the band above [top] or below [bottom], and capped beyond.
 */
internal fun dragAutoscrollSpeed(y: Float, top: Float, bottom: Float, edge: Float, maxSpeed: Float): Float = when {
    edge <= 0f -> 0f
    y < top + edge -> -maxSpeed * ((top + edge - y) / edge).coerceIn(0f, 1f)
    y > bottom - edge -> maxSpeed * ((y - bottom + edge) / edge).coerceIn(0f, 1f)
    else -> 0f
}

/** Long-press drag selection with edge autoscroll, and pinching between [GridLevel]s through [pinch]. */
@Composable
internal fun Modifier.galleryGestures(grid: LazyGridState, mediaIds: List<String>, selection: Set<String>,
    pinch: GridPinch, select: (Set<String>, Boolean) -> Unit): Modifier {
    val ids by rememberUpdatedState(mediaIds)
    val known by rememberUpdatedState(remember(mediaIds) { mediaIds.toHashSet() })
    val selected by rememberUpdatedState(selection)
    val selectNow by rememberUpdatedState(select)
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edge = with(density) { GalleryFeel.AUTOSCROLL_EDGE.toPx() }
    val maxSpeed = GalleryFeel.AUTOSCROLL_MAX_DP_PER_SECOND * density.density
    var pointer by remember { mutableStateOf<Offset?>(null) }
    var anchor by remember { mutableStateOf<String?>(null) }
    var baseline by remember { mutableStateOf(emptySet<String>()) }
    var previous by remember { mutableStateOf(emptySet<String>()) }
    fun hit(point: Offset): String? = nearestInRow(grid.layoutInfo.visibleItemsInfo.filter { it.key in known }.map { it.key as String to it.rect() }, point)
    fun update(point: Offset) {
        val first = anchor ?: return
        val last = hit(point) ?: return
        val range = selectionRange(ids, first, last)
        if (range == previous) return
        val restored = previous - range
        selectNow(restored.intersect(baseline), true)
        selectNow(restored - baseline, false)
        // A long press only ever adds: starting on a selected photo keeps it, like Google Photos.
        selectNow(range, true)
        if (previous.isNotEmpty()) haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        previous = range
    }
    val dragging = anchor != null
    LaunchedEffect(dragging) {
        if (dragging) grid.autoscrollDrag(edge, maxSpeed, active = { anchor != null }, finger = { pointer }, extend = ::update)
    }
    return this.gridPinchInput(pinch) { anchor != null }.pointerInput(grid) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = awaitLongPressOrCancellation(down.id)
            if (press != null) {
                hit(press.position)?.let { id ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    baseline = selected; previous = emptySet()
                    anchor = id; pointer = press.position; update(press.position)
                }
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        // A second finger ends the drag.
                        val single = event.changes.count { it.pressed } <= 1
                        if (change != null && single && anchor != null) { change.consume(); pointer = change.position; update(change.position) }
                    } while (single && change?.pressed == true)
                } finally { anchor = null; pointer = null }
            }
        }
    }
}

/** Two-finger pinches between grid levels, claimed before the grid can scroll or a tile can be pressed. */
private fun Modifier.gridPinchInput(pinch: GridPinch, dragSelecting: () -> Boolean): Modifier = pointerInput(pinch) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var pinching = false
            var ended = false
            var startSpan = 0f
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2 && !ended && !dragSelecting()) {
                    val span = event.pressedSpan()
                    if (startSpan == 0f) startSpan = span
                    if (!pinching && abs(span - startSpan) >= viewConfiguration.touchSlop && startSpan > 0f) {
                        pinching = pinch.begin(event.calculateCentroid(useCurrent = true))
                        startSpan = span
                        if (!pinching) ended = true
                    }
                    if (pinching) pinch.update(span / startSpan)
                } else if (pinching && !ended) {
                    // Lifting a finger ends the pinch; the other finger must not then scroll the grid.
                    pinch.end()
                    ended = true
                }
                if (pinching || ended) event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
            if (pinching && !ended) pinch.end()
        }
}

/**
 * The fingers' mean distance from their centroid. Unlike [calculateCentroidSize] it counts a finger from the event it
 * lands in, so the pinch measures from where both fingers went down rather than from their first move.
 */
private fun PointerEvent.pressedSpan(): Float {
    val points = changes.filter { it.pressed }.map { it.position }
    if (points.size < 2) return 0f
    val centroid = points.fold(Offset.Zero) { sum, point -> sum + point } / points.size.toFloat()
    return points.sumOf { (it - centroid).getDistance().toDouble() }.toFloat() / points.size
}

/**
 * Scrolls once per frame while [active] and the [finger] is in an edge band, by time rather than by frame count, and
 * re-applies the still finger through [extend] so the range keeps growing as rows pass under it.
 */
private suspend fun LazyGridState.autoscrollDrag(edge: Float, maxSpeed: Float, active: () -> Boolean, finger: () -> Offset?,
    extend: (Offset) -> Unit) {
    var last = withFrameNanos { it }
    while (active()) {
        val now = withFrameNanos { it }
        val seconds = (now - last) / NANOS_PER_SECOND
        last = now
        val point = finger()
        val bottom = (layoutInfo.viewportSize.height - layoutInfo.afterContentPadding).toFloat()
        val speed = if (point == null) 0f else dragAutoscrollSpeed(point.y, 0f, bottom, edge, maxSpeed)
        if (point != null && speed != 0f) {
            scrollBy(speed * seconds)
            extend(point)
        }
    }
}

private const val NANOS_PER_SECOND = 1_000_000_000f
