package dev.mela.app.ui

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.geometry.Rect
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

// Use a single half-circle: corner shapes can shrink their radii to this tab's narrow width.
private val ScrubberTabShape = GenericShape { size, _ ->
    val radius = size.height / 2f
    moveTo(radius, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height)
    lineTo(radius, size.height)
    arcTo(Rect(0f, 0f, size.height, size.height), 90f, 180f, false)
    close()
}

internal data class GalleryScrollLabel(val index: Int, val month: String, val year: Int)

@Composable
internal fun GalleryFastScroll(grid: LazyGridState, labels: List<GalleryScrollLabel>, fullSpanEstimates: Map<Int, Float>,
    minimumTileDp: Int, modifier: Modifier = Modifier, bottomSpace: androidx.compose.ui.unit.Dp = 108.dp) {
    if (labels.isEmpty()) return
    val density = LocalDensity.current
    val spacing = with(density) { 2.dp.toPx() }
    val handlePx = with(density) { 56.dp.toPx() }
    val revealDistance = with(density) { 20.dp.toPx() }
    val layout = grid.layoutInfo
    val columns = ((layout.viewportSize.width + spacing) / (with(density) { minimumTileDp.dp.toPx() } + spacing)).toInt().coerceAtLeast(1)
    val tileHeight = ((layout.viewportSize.width - spacing * (columns - 1)) / columns).coerceAtLeast(1f)
    var measured by remember(fullSpanEstimates.keys, columns) { mutableStateOf(emptyMap<Int, Float>()) }
    LaunchedEffect(grid, fullSpanEstimates.keys, columns) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.filter { it.index in fullSpanEstimates }.associate { it.index to it.size.height.toFloat() } }
            .collect { visible -> if (visible.any { measured[it.key] != it.value }) measured = measured + visible }
    }
    val metrics = remember(layout.totalItemsCount, columns, tileHeight, measured, fullSpanEstimates, layout.viewportSize.height, layout.afterContentPadding) {
        GalleryScrollMetrics(layout.totalItemsCount, columns, tileHeight, spacing, fullSpanEstimates + measured, layout.viewportSize.height, layout.afterContentPadding)
    }
    val currentMetrics by rememberUpdatedState(metrics)
    val fraction = if (metrics.maxScroll > 0) metrics.position(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset) / metrics.maxScroll else 0f
    val currentFraction by rememberUpdatedState(fraction)
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var gripTop by remember { mutableFloatStateOf(0f) }
    var visible by remember { mutableStateOf(false) }
    var lastMovement by remember { mutableLongStateOf(0L) }
    var height by remember { mutableIntStateOf(0) }
    var dateHeight by remember(density) { mutableIntStateOf(with(density) { 28.dp.roundToPx() }) }
    val travel = (height - handlePx).coerceAtLeast(1f)
    val currentTravel by rememberUpdatedState(travel)
    val targets = remember { Channel<Float>(Channel.CONFLATED) }
    DisposableEffect(targets) { onDispose { targets.close() } }
    LaunchedEffect(grid, targets) {
        for (target in targets) {
            val (index, offset) = currentMetrics.target(target)
            if (grid.layoutInfo.totalItemsCount > index) grid.scrollToItem(index, offset)
        }
    }
    LaunchedEffect(grid) {
        var previous = 0f
        var accumulated = 0f
        var lastTime = 0L
        snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            val now = SystemClock.uptimeMillis()
            val position = currentMetrics.position(index, offset)
            if (now - lastTime > 300) accumulated = 0f
            if (grid.isScrollInProgress && !dragging) {
                accumulated += abs(position - previous)
                if (accumulated >= revealDistance) { visible = true; lastMovement = now }
            }
            previous = position
            lastTime = now
        }
    }
    LaunchedEffect(lastMovement, dragging) {
        if (!dragging) { delay(2400); visible = false }
    }
    val active = dragging || visible
    val activeNow by rememberUpdatedState(active)
    val thumbFraction = if (dragging) dragFraction else fraction
    val thumbTop = thumbFraction * travel
    val thumbTopNow by rememberUpdatedState(thumbTop)
    val hiddenOffset by animateFloatAsState(if (active) 0f else handlePx, tween(180), label = "scrubber-slide")
    val currentIndex = if (dragging) metrics.target(dragFraction).first else grid.firstVisibleItemIndex
    val label = labels.lastOrNull { it.index <= currentIndex } ?: labels.first()
    Box(modifier.width(180.dp).padding(bottom = bottomSpace, top = 12.dp).clipToBounds().onSizeChanged { height = it.height }
        .semantics {
            contentDescription = "${labels.first().month} – ${labels.last().month}"
            stateDescription = label.month
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            setProgress { visible = true; lastMovement = SystemClock.uptimeMillis(); targets.trySend(it.coerceIn(0f, 1f)); true }
        }.testTag("gallery-fast-scroll")) {
        if (dragging) {
            var lastY = -handlePx
            labels.distinctBy { it.year }.forEach { year ->
                val y = if (metrics.maxScroll > 0) (metrics.topOf(year.index) / metrics.maxScroll).coerceIn(0f, 1f) * travel else 0f
                if (y - lastY >= handlePx * .65f) {
                    lastY = y
                    Surface(Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y.roundToInt()) }.padding(end = 48.dp).testTag("fast-scroll-year-${year.year}"), shape = CircleShape,
                        color = Color.Black.copy(alpha = .82f), contentColor = Color.White) {
                        Text(year.year.toString(), Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Surface(Modifier.align(Alignment.TopEnd).offset { IntOffset(0, (thumbTop + (handlePx - dateHeight) / 2f).roundToInt()) }
                .padding(end = 48.dp).onSizeChanged { dateHeight = it.height }.testTag("fast-scroll-date"),
                shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(label.month, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
        val inputTop = if (dragging) gripTop else thumbTop
        Box(Modifier.align(Alignment.TopEnd).offset { IntOffset(with(density) { 8.dp.roundToPx() }, inputTop.roundToInt()) }
            .graphicsLayer { translationX = hiddenOffset }.size(56.dp).testTag("fast-scroll-handle")
            .pointerInput(grid) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!activeNow) return@awaitEachGesture
                    down.consume()
                    gripTop = thumbTopNow
                    dragging = true
                    dragFraction = currentFraction
                    // Keep the gesture's coordinate space fixed while its visible thumb follows the finger.
                    try {
                        verticalDrag(down.id) { change ->
                            dragFraction = (dragFraction + change.positionChange().y / currentTravel).coerceIn(0f, 1f)
                            targets.trySend(dragFraction)
                            change.consume()
                        }
                    } finally {
                        dragging = false
                        visible = true
                        lastMovement = SystemClock.uptimeMillis()
                    }
                }
            }) {
            Surface(Modifier.align(Alignment.CenterEnd).offset(x = (-8).dp).size(width = 32.dp, height = 40.dp).graphicsLayer { translationY = thumbTop - inputTop },
                shape = ScrubberTabShape,
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
