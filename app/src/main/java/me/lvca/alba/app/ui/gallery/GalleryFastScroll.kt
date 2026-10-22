package me.lvca.alba.app.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.designsystem.MediaPill
import me.lvca.alba.app.ui.viewer.reduceGalleryMotion
import kotlin.math.abs
import kotlin.math.roundToInt

private val HandleWidth = 48.dp

internal data class GalleryScrollLabel(val index: Int, val month: String, val year: Int)

/**
 * Google Photos' date scrubber: each month gets an equal share of the track and ticks as the thumb crosses it. It
 * appears after a little scrolling on a grid of at least [GalleryFeel.SCRUBBER_MIN_ITEMS] items, can be grabbed as soon
 * as any of it shows, and hides again once things settle.
 */
@Composable
internal fun GalleryFastScroll(grid: LazyGridState, labels: List<GalleryScrollLabel>, fullSpanEstimates: Map<Int, Float>,
    columns: Int, modifier: Modifier = Modifier, bottomSpace: Dp = 108.dp) {
    // Like Google Photos, a short grid gets no scrubber; ordinary scrolling covers it.
    if (labels.isEmpty() || !GalleryFeel.scrubberAvailable(grid.layoutInfo.totalItemsCount)) return
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val reducedMotion = reduceGalleryMotion()
    val spacing = with(density) { GalleryFeel.GRID_SPACING.toPx() }
    // The touch box is a little wider than the 40dp thumb so it meets the 48dp minimum target.
    val handleWidthPx = with(density) { HandleWidth.toPx() }
    val thumbHeightPx = with(density) { GalleryFeel.SCRUBBER_THUMB_HEIGHT.toPx() }
    val revealDistance = with(density) { GalleryFeel.SCRUBBER_REVEAL_DISTANCE.toPx() }
    val layout = grid.layoutInfo
    val tileHeight = ((layout.viewportSize.width - spacing * (columns - 1)) / columns.coerceAtLeast(1)).coerceAtLeast(1f)
    var measured by remember(fullSpanEstimates.keys, columns) { mutableStateOf(emptyMap<Int, Float>()) }
    LaunchedEffect(grid, fullSpanEstimates.keys, columns) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.filter { it.index in fullSpanEstimates }.associate { it.index to it.size.height.toFloat() } }
            .collect { visible -> if (visible.any { measured[it.key] != it.value }) measured = measured + visible }
    }
    val metrics = remember(layout.totalItemsCount, columns, tileHeight, measured, fullSpanEstimates, layout.viewportSize.height, layout.afterContentPadding) {
        GalleryScrollMetrics(layout.totalItemsCount, columns, tileHeight, spacing, fullSpanEstimates + measured, layout.viewportSize.height, layout.afterContentPadding)
    }
    val currentMetrics by rememberUpdatedState(metrics)
    val buckets = remember(metrics, labels) { MonthBuckets(labels.map { metrics.topOf(it.index) }, metrics.maxScroll) }
    val currentBuckets by rememberUpdatedState(buckets)
    val position = metrics.position(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset)
    val fraction = buckets.fractionAt(position)
    val currentFraction by rememberUpdatedState(fraction)
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var gripTop by remember { mutableFloatStateOf(0f) }
    var visible by remember { mutableStateOf(false) }
    // Hiding waits longer after scrolling than after letting go of the thumb.
    var hideDelay by remember { mutableLongStateOf(GalleryFeel.SCRUBBER_IDLE_HIDE_MILLIS) }
    var hideRequest by remember { mutableIntStateOf(0) }
    var height by remember { mutableIntStateOf(0) }
    val travel = (height - thumbHeightPx).coerceAtLeast(1f)
    val currentTravel by rememberUpdatedState(travel)
    val months = remember { Channel<Int>(Channel.CONFLATED) }
    DisposableEffect(months) { onDispose { months.close() } }
    LaunchedEffect(grid, months) {
        for (month in months) {
            val index = labels.getOrNull(month)?.index ?: continue
            if (grid.layoutInfo.totalItemsCount > index) grid.scrollToItem(index)
        }
    }
    LaunchedEffect(grid) {
        var previous = Float.NaN
        var accumulated = 0f
        snapshotFlow { Triple(grid.isScrollInProgress, grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset) }.collect { (scrolling, index, offset) ->
            val now = currentMetrics.position(index, offset)
            if (!scrolling) {
                // The distance counts from the start of each scroll.
                accumulated = 0f
                previous = now
                if (visible && !dragging) { hideDelay = GalleryFeel.SCRUBBER_IDLE_HIDE_MILLIS; hideRequest++ }
                return@collect
            }
            if (!dragging) {
                if (!previous.isNaN()) accumulated += abs(now - previous)
                if (accumulated >= revealDistance) { visible = true; hideRequest++ }
            }
            previous = now
        }
    }
    LaunchedEffect(hideRequest, dragging) {
        if (dragging || grid.isScrollInProgress) return@LaunchedEffect
        delay(hideDelay)
        if (!grid.isScrollInProgress) visible = false
    }
    val active = dragging || visible
    val thumbFraction = if (dragging) dragFraction else fraction
    val thumbTop = thumbFraction * travel
    val thumbTopNow by rememberUpdatedState(thumbTop)
    val hiddenOffset by animateFloatAsState(if (active) 0f else handleWidthPx,
        if (reducedMotion) snap() else tween(if (active) GalleryFeel.SCRUBBER_SHOW_MILLIS else GalleryFeel.SCRUBBER_HIDE_MILLIS), label = "scrubber-slide")
    val hiddenNow by rememberUpdatedState(hiddenOffset)
    val currentMonth = if (dragging) buckets.monthAt(dragFraction) else labels.indexOfLast { it.index <= grid.firstVisibleItemIndex }.coerceAtLeast(0)
    val label = labels.getOrElse(currentMonth) { labels.first() }
    val span = stringResource(R.string.month_span, labels.first().month, labels.last().month)
    Box(modifier.width(180.dp).padding(bottom = bottomSpace, top = 12.dp).clipToBounds().onSizeChanged { height = it.height }
        .semantics {
            contentDescription = span
            stateDescription = label.month
            progressBarRangeInfo = ProgressBarRangeInfo(thumbFraction, 0f..1f)
            setProgress {
                visible = true; hideDelay = GalleryFeel.SCRUBBER_IDLE_HIDE_MILLIS; hideRequest++
                months.trySend(currentBuckets.monthAt(it)); true
            }
        }.testTag("gallery-fast-scroll")) {
        // Year labels mark the track only while dragging, at each year's first month.
        AnimatedVisibility(dragging, Modifier.matchParentSize(),
            enter = if (reducedMotion) fadeIn(snap()) else fadeIn(tween(GalleryFeel.SCRUBBER_LABEL_IN_MILLIS)),
            exit = if (reducedMotion) fadeOut(snap()) else fadeOut(tween(GalleryFeel.SCRUBBER_LABEL_OUT_MILLIS))) {
            Box(Modifier.fillMaxSize()) {
                val minGap = with(density) { 28.dp.toPx() }
                var lastY = -minGap
                labels.withIndex().distinctBy { it.value.year }.forEach { (month, year) ->
                    val y = buckets.fractionOf(month) * travel
                    if (y - lastY >= minGap) {
                        lastY = y
                        MediaPill(Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y.roundToInt()) }
                            .padding(end = GalleryFeel.SCRUBBER_THUMB_WIDTH + 20.dp).testTag("fast-scroll-year-${year.year}"),
                            strong = true, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 3.dp)) {
                            Text(year.year.toString(), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        val inputTop = if (dragging) gripTop else thumbTop
        Box(Modifier.align(Alignment.TopEnd).offset { IntOffset(0, inputTop.roundToInt()) }
            .graphicsLayer { translationX = hiddenOffset }
            .size(width = HandleWidth, height = GalleryFeel.SCRUBBER_THUMB_HEIGHT).testTag("fast-scroll-handle")
            .pointerInput(grid) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // Grabbable whenever any of it shows, including while it slides in during a fling.
                    if (hiddenNow >= handleWidthPx - 1f) return@awaitEachGesture
                    down.consume()
                    scope.launch { grid.stopScroll() }
                    gripTop = thumbTopNow
                    dragFraction = currentFraction
                    var month = currentBuckets.monthAt(dragFraction)
                    dragging = true
                    // Keep the gesture's coordinate space fixed while its visible thumb follows the finger.
                    try {
                        verticalDrag(down.id) { change ->
                            dragFraction = (dragFraction + change.positionChange().y / currentTravel).coerceIn(0f, 1f)
                            val next = currentBuckets.monthAt(dragFraction)
                            if (next != month) {
                                month = next
                                months.trySend(next)
                                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            }
                            change.consume()
                        }
                    } finally {
                        dragging = false
                        visible = true
                        hideDelay = GalleryFeel.SCRUBBER_DRAG_HIDE_MILLIS
                        hideRequest++
                    }
                }
            }) {
            Surface(Modifier.align(Alignment.CenterEnd).size(GalleryFeel.SCRUBBER_THUMB_WIDTH, GalleryFeel.SCRUBBER_THUMB_HEIGHT)
                .graphicsLayer { translationY = thumbTop - inputTop },
                shape = RoundedCornerShape(topStart = GalleryFeel.SCRUBBER_THUMB_WIDTH / 2, bottomStart = GalleryFeel.SCRUBBER_THUMB_WIDTH / 2),
                color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 0.5.dp) {
            val ink = MaterialTheme.colorScheme.onSurfaceVariant
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(width = 12.dp, height = 16.dp)) {
                    drawPath(Path().apply { moveTo(size.width / 2, 0f); lineTo(size.width, size.height * .36f); lineTo(0f, size.height * .36f); close() }, ink)
                    drawPath(Path().apply { moveTo(0f, size.height * .64f); lineTo(size.width, size.height * .64f); lineTo(size.width / 2, size.height); close() }, ink)
                }
            }
            }
        }
    }
}
