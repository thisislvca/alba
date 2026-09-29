package dev.mela.app.ui

import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.mela.engine.model.GalleryMedia
import kotlinx.coroutines.delay

/** Selection uses a fixed starting snapshot so dragging back never leaves stray selected items. */
internal fun selectionRange(ids: List<String>, anchor: String, end: String): Set<String> {
    val a = ids.indexOf(anchor); val b = ids.indexOf(end)
    return if (a < 0 || b < 0) emptySet() else ids.subList(minOf(a, b), maxOf(a, b) + 1).toSet()
}

@Composable
internal fun Modifier.galleryGestures(grid: LazyGridState, items: List<GalleryMedia>, selection: Set<String>,
    tileSize: Int, resize: (Int) -> Unit, indexForKey: (String, Int) -> Int, select: (Set<String>, Boolean) -> Unit): Modifier {
    val ids by rememberUpdatedState(items.map { it.id })
    val selected by rememberUpdatedState(selection)
    val selectNow by rememberUpdatedState(select)
    val resizeNow by rememberUpdatedState(resize)
    val indexNow by rememberUpdatedState(indexForKey)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val sizeNow by rememberUpdatedState(tileSize)
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    var pointer by remember { mutableStateOf<Offset?>(null) }
    var anchor by remember { mutableStateOf<String?>(null) }
    var baseline by remember { mutableStateOf(emptySet<String>()) }
    var previous by remember { mutableStateOf(emptySet<String>()) }
    var selecting by remember { mutableStateOf(true) }
    fun hit(point: Offset): String? = grid.layoutInfo.visibleItemsInfo.firstOrNull {
        point.x >= it.offset.x && point.x < it.offset.x + it.size.width && point.y >= it.offset.y && point.y < it.offset.y + it.size.height
    }?.key?.toString()?.takeIf { it in ids }
    fun update(point: Offset) {
        val first = anchor ?: return
        val last = hit(point) ?: return
        val range = selectionRange(ids, first, last)
        val restored = previous - range
        selectNow(restored.intersect(baseline), true)
        selectNow(restored - baseline, false)
        selectNow(range, selecting)
        previous = range
    }
    LaunchedEffect(anchor) {
        while (anchor != null) {
            val p = pointer
            if (p != null) {
                val height = grid.layoutInfo.viewportSize.height.toFloat()
                val speed = when { p.y < edge -> -((edge - p.y) / edge).coerceIn(0f, 1f) * 24f
                    p.y > height - edge -> ((p.y - height + edge) / edge).coerceIn(0f, 1f) * 24f
                    else -> 0f }
                if (speed != 0f) { grid.scrollBy(speed); update(p) }
            }
            delay(16)
        }
    }
    return this.pointerInput(grid) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = awaitLongPressOrCancellation(down.id)
            if (press != null) {
                hit(press.position)?.let { id ->
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    baseline = selected; selecting = id !in baseline; previous = emptySet()
                    anchor = id; pointer = press.position; update(press.position)
                }
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (anchor != null) { change.consume(); pointer = change.position; update(change.position) }
                    } while (change.pressed)
                } finally { anchor = null; pointer = null }
            }
        }
    }.pointerInput(grid) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var scale = 1f
            var changed = false
            do {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2) {
                    scale *= event.calculateZoom()
                    event.changes.forEach { it.consume() }
                    if (!changed && (scale > 1.22f || scale < .82f)) {
                        val center = event.calculateCentroid()
                        val item = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key.toString() == hit(center) }
                        val sizes = listOf(80, 112, 160)
                        val next = sizes[(sizes.indexOf(sizeNow).coerceAtLeast(0) + if (scale > 1f) 1 else -1).coerceIn(0, 2)]
                        if (next != sizeNow) {
                            // Apply the new index and column count in the same measure pass.
                            // A delayed scrollToItem interrupts reflow and causes a second jump.
                            item?.let { target -> grid.requestScrollToItem(indexNow(target.key.toString(), next), -target.offset.y) }
                            resizeNow(next)
                        }
                        changed = true
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
