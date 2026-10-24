package me.lvca.alba.app.ui.viewer

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Half the travel available to a photo's pan offset at [zoom] (the photo may not leave the screen). */
internal fun photoPanExtent(viewport: IntSize, image: IntSize, zoom: Float): Offset {
    if (image.width <= 0 || image.height <= 0) return Offset.Zero
    val fit = min(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
    return Offset(
        ((image.width * fit * zoom - viewport.width) / 2).coerceAtLeast(0f),
        ((image.height * fit * zoom - viewport.height) / 2).coerceAtLeast(0f),
    )
}

private const val FIT_TOLERANCE = 1.01f

private fun IntSize.isEmpty() = width <= 0 || height <= 0

/** Moves the offset so that [focus] (relative to the viewport centre) stays put while zoom goes [from] → [to]. */
internal fun zoomAroundOffset(offset: Offset, from: Float, to: Float, focus: Offset): Offset =
    if (from <= 0f) offset else focus - (focus - offset) * (to / from)

/**
 * GP's max zoom: stop at 150 source pixels per screen inch, but never below 2× or the scale at
 * which the photo fills the screen.
 */
internal fun maxPhotoZoom(source: IntSize, image: IntSize, viewport: IntSize, dpi: Float): Float {
    if (image.isEmpty() || viewport.isEmpty()) return ViewerSpec.MIN_MAX_ZOOM
    val fit = min(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
    val fill = max(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height) / fit
    val fittedLongPx = max(image.width, image.height) * fit
    val resolution = if (source.width > 0 && source.height > 0) {
        max(source.width, source.height) * dpi / (fittedLongPx * ViewerSpec.MAX_ZOOM_SOURCE_PPI)
    } else {
        ViewerSpec.UNKNOWN_MAX_ZOOM
    }
    return maxOf(resolution, ViewerSpec.MIN_MAX_ZOOM, fill)
}

/**
 * GP's 1× detent. While [wasSnapped] the scale holds at exactly 1× until the fitted long side is
 * more than 8dp outside the +16dp/−28dp window; otherwise it snaps once it enters the window.
 */
internal fun zoomDetentSnapped(rawZoom: Float, wasSnapped: Boolean, fittedLongPx: Float, density: Float): Boolean {
    if (fittedLongPx <= 0f) return false
    val release = if (wasSnapped) ViewerSpec.DetentRelease.value * density else 0f
    val above = 1f + (ViewerSpec.DetentAbove.value * density + release) / fittedLongPx
    val below = 1f - (ViewerSpec.DetentBelow.value * density + release) / fittedLongPx
    return rawZoom in below..above
}

/**
 * The double-tap target. Zooming in keeps the tapped point under the finger, but a tap on the
 * letterbox zooms toward the nearest edge of the photo rather than into empty canvas.
 */
internal fun doubleTapZoomTarget(
    tap: Offset, viewport: IntSize, image: IntSize, zoom: Float, offset: Offset, maxZoom: Float,
): Pair<Float, Offset> {
    if (zoom > FIT_TOLERANCE || image.isEmpty()) return 1f to Offset.Zero
    val target = min(ViewerSpec.DOUBLE_TAP_ZOOM, maxZoom)
    val fit = min(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
    val halfWidth = image.width * fit * zoom / 2f
    val halfHeight = image.height * fit * zoom / 2f
    val focus = Offset(
        (tap.x - viewport.width / 2f).coerceIn(offset.x - halfWidth, offset.x + halfWidth),
        (tap.y - viewport.height / 2f).coerceIn(offset.y - halfHeight, offset.y + halfHeight),
    )
    val extent = photoPanExtent(viewport, image, target)
    val next = zoomAroundOffset(offset, zoom, target, focus)
    return target to Offset(next.x.coerceIn(-extent.x, extent.x), next.y.coerceIn(-extent.y, extent.y))
}

/** Zoom and pan of one viewer photo. [zoom] and [offset] drive the photo's graphics layer. */
@Stable
internal class PhotoZoomState {
    var zoom by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    // Plain fields refreshed by the composable; only gestures read them.
    var viewport = IntSize.Zero
    var image = IntSize.Zero
    var source = IntSize.Zero
    var dpi = 160f
    private var animation: Job? = null

    val maxZoom: Float get() = maxPhotoZoom(source, image, viewport, dpi)
    val fittedLongPx: Float get() {
        if (image.width <= 0 || image.height <= 0) return 0f
        val fit = min(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
        return max(image.width, image.height) * fit
    }

    fun stop() {
        animation?.cancel()
        animation = null
    }

    fun launch(scope: CoroutineScope, block: suspend () -> Unit) {
        stop()
        animation = scope.launch { block() }
    }

    private fun constrain(value: Offset, atZoom: Float = zoom): Offset {
        val extent = photoPanExtent(viewport, image, atZoom)
        return Offset(value.x.coerceIn(-extent.x, extent.x), value.y.coerceIn(-extent.y, extent.y))
    }

    private fun center() = Offset(viewport.width / 2f, viewport.height / 2f)

    /** [focus] is in viewport coordinates. */
    fun zoomAround(next: Float, focus: Offset) {
        offset = constrain(zoomAroundOffset(offset, zoom, next, focus - center()), next)
        zoom = next
    }

    /** Pans by [delta], stopping at the photo's edges. */
    fun panBy(delta: Offset) {
        offset = constrain(offset + delta)
    }

    suspend fun animateTo(targetZoom: Float, targetOffset: Offset, reduceMotion: Boolean) {
        if (reduceMotion) {
            zoom = targetZoom
            offset = targetOffset
            return
        }
        val startZoom = zoom
        val startOffset = offset
        animate(0f, 1f, animationSpec = tween(ViewerSpec.ZOOM_MILLIS, easing = FastOutSlowInEasing)) { t, _ ->
            zoom = lerp(startZoom, targetZoom, t)
            offset = lerp(startOffset, targetOffset, t)
        }
    }

    /** Double tap: fit ↔ 2.5× around [tap] (viewport coordinates). */
    suspend fun toggle(tap: Offset, reduceMotion: Boolean) {
        val (targetZoom, targetOffset) = doubleTapZoomTarget(tap, viewport, image, zoom, offset, maxZoom)
        animateTo(targetZoom, targetOffset, reduceMotion)
    }

    /** Keeps a zoomed pan moving after the finger lifts, stopping at the photo's edges. */
    suspend fun fling(velocity: Velocity, decay: DecayAnimationSpec<Float>) = coroutineScope {
        val extent = photoPanExtent(viewport, image, zoom)
        launch {
            AnimationState(offset.x, velocity.x).animateDecay(decay) {
                val clamped = value.coerceIn(-extent.x, extent.x)
                offset = offset.copy(x = clamped)
                if (clamped != value) cancelAnimation()
            }
        }
        launch {
            AnimationState(offset.y, velocity.y).animateDecay(decay) {
                val clamped = value.coerceIn(-extent.y, extent.y)
                offset = offset.copy(y = clamped)
                if (clamped != value) cancelAnimation()
            }
        }
    }
}

/** The viewer's side of a pinch to close; the photo's zoom gestures drive it. */
interface PinchToClose {
    /** The photo should be drawn at [scale] (below 1) and offset by [translation] from its resting place. */
    fun pinch(scale: Float, translation: Offset)

    /** The fingers lifted, or the gesture was [cancelled]: close or return to fit. */
    fun release(cancelled: Boolean)
}

/**
 * A pinch to close. The photo shrinks with the fingers and keeps the point under them at [anchor] (relative to the
 * viewport centre, where the photo shrinks around), following the fingers' pan as well.
 */
internal class PinchCloseTracker(private val anchor: Offset) {
    var scale = 1f
        private set
    private var pan = Offset.Zero

    val translation: Offset get() = anchor * (1f - scale) + pan

    fun update(zoomChange: Float, panDelta: Offset) {
        scale = (scale * zoomChange).coerceIn(ViewerSpec.PINCH_CLOSE_MIN_SCALE, 1f)
        pan += panDelta
    }
}

/**
 * GP enters its pinch to close when a two-finger gesture starts at fit scale without spreading the fingers; a
 * spreading pinch zooms in instead.
 */
internal fun startsPinchToClose(zoom: Float, pinchMotion: Float, pressed: Int): Boolean =
    pressed > 1 && zoom <= 1f && pinchMotion <= 1f

/** One pinch/pan gesture on a photo, from first finger down to last finger up. */
private class ZoomGesture(
    private val state: PhotoZoomState,
    private val density: Float,
    private val tick: () -> Unit,
) {
    val tracker = VelocityTracker()
    var transforming = false
        private set

    /** Set when this gesture became a pinch to close rather than a zoom. */
    var closing: PinchCloseTracker? = null
        private set
    private var pinchMotion = 1f
    private var panMotion = Offset.Zero
    private var rawZoom = state.zoom
    private var snapped = state.zoom == 1f
    private var pointers = 1

    /**
     * Pinch with two fingers anywhere; pan with one only once zoomed, so fit-scale drags stay with the viewer. When
     * [canClose], a two-finger gesture at fit that does not spread the fingers becomes a pinch to close around
     * [centroid] (relative to the viewport centre).
     */
    fun claim(pressed: Int, zoomChange: Float, pan: Offset, centroidSize: Float, slop: Float, canClose: Boolean, centroid: Offset): Boolean {
        pinchMotion *= zoomChange
        panMotion += pan
        val pinchPx = abs(1f - pinchMotion) * centroidSize
        val moved = panMotion.getDistance() > slop
        transforming = if (pressed > 1) pinchPx > slop || moved else state.zoom > 1f && moved
        if (transforming && canClose && startsPinchToClose(state.zoom, pinchMotion, pressed)) closing = PinchCloseTracker(centroid)
        return transforming
    }

    fun pointersChanged(pressed: Int) {
        // The centroid jumps when a finger lands or lifts; restart velocity from here.
        if (pressed != pointers) tracker.resetTracking()
        pointers = pressed
    }

    fun zoom(zoomChange: Float, centroid: Offset) {
        if (zoomChange == 1f) return
        val before = state.zoom
        val max = state.maxZoom
        // GP clamps pinch hard to [1, max]; there is no overscale.
        rawZoom = (rawZoom * zoomChange).coerceIn(1f, max)
        val nowSnapped = zoomDetentSnapped(rawZoom, snapped, state.fittedLongPx, density)
        val next = if (nowSnapped) 1f else rawZoom
        // GP ticks (VIRTUAL_KEY) when the pinch lands on 1× or reaches the maximum.
        val landedOnFit = nowSnapped && !snapped
        val reachedMax = before < max && next >= max
        if (landedOnFit || reachedMax) tick()
        snapped = nowSnapped
        state.zoomAround(next, centroid)
    }

    /** A zoom (not a pinch to close) that leaves the photo zoomed in, so a pan keeps moving after release. */
    val zoomedByPinch: Boolean get() = transforming && closing == null && state.zoom > 1f

    fun pan(pan: Offset) {
        // GP clamps the pan at the photo's edges; the pager only pages at fit scale.
        state.panBy(pan)
    }
}

/**
 * Pinch (anchored to the fingers' centroid, clamped to [1, max] with GP's 1× detent) and pan with fling for a zoomed
 * photo. A pinch in at fit scale is handed to [pinchToClose] when there is one. A single finger on a photo at fit is
 * left alone, so the pager, swipe-down and the details sheet keep working.
 */
@Composable
internal fun Modifier.photoZoomGestures(state: PhotoZoomState, reduceMotion: Boolean, pinchToClose: PinchToClose? = null): Modifier {
    val haptics = LocalHapticFeedback.current
    val reduce by rememberUpdatedState(reduceMotion)
    val closer by rememberUpdatedState(pinchToClose)
    return pointerInput(state) {
        val decay = splineBasedDecay<Float>(this)
        coroutineScope {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                state.stop()
                val gesture = ZoomGesture(state, density) {
                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                }
                // Whoever was told about this pinch to close hears how it ends, even if the viewer changed meanwhile.
                var closing: PinchToClose? = null
                try {
                    do {
                        val event = awaitPointerEvent()
                        if (!gesture.transforming && event.changes.any { it.isConsumed }) return@awaitEachGesture
                        val pressed = event.changes.count { it.pressed }
                        gesture.pointersChanged(pressed)
                        val zoomChange = event.calculateZoom()
                        val pan = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = true)
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val active = gesture.transforming || gesture.claim(pressed, zoomChange, pan,
                            event.calculateCentroidSize(useCurrent = true), viewConfiguration.touchSlop,
                            canClose = closer != null && centroid.isSpecified, centroid = if (centroid.isSpecified) centroid - center else Offset.Zero)
                        if (active && pressed > 0 && centroid.isSpecified) {
                            val close = gesture.closing
                            if (close != null) {
                                close.update(zoomChange, pan)
                                closing = closing ?: closer
                                closing?.pinch(close.scale, close.translation)
                            } else {
                                gesture.zoom(zoomChange, centroid)
                                gesture.pan(pan)
                                gesture.tracker.addPosition(event.changes.first().uptimeMillis, centroid)
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                } catch (cancelled: CancellationException) {
                    closing?.release(cancelled = true)
                    throw cancelled
                }
                closing?.release(cancelled = false)
                if (gesture.zoomedByPinch && !reduce) {
                    val velocity = gesture.tracker.calculateVelocity()
                    state.launch(this@coroutineScope) { state.fling(velocity, decay) }
                }
            }
        }
    }
}

/**
 * The second press of a double tap. Released in place it toggles the zoom; once it travels past touch slop mostly
 * vertically it becomes GP's quick scale (double-tap and drag): dragging down zooms in and dragging up zooms out. A
 * mostly horizontal drag is [abandoned] to the pager.
 */
internal class QuickScaleTracker(private val slopPx: Float, private val distancePx: Float) {
    var scaling = false
        private set
    var abandoned = false
        private set
    private var travel = Offset.Zero

    /** The zoom factor for a move of [delta]: 1 while still deciding, then exponential in the vertical travel. */
    fun move(delta: Offset): Float {
        if (abandoned) return 1f
        if (!scaling) {
            travel += delta
            if (travel.getDistance() <= slopPx) return 1f
            if (abs(travel.x) > abs(travel.y)) {
                abandoned = true
                return 1f
            }
            scaling = true
        }
        return quickScaleFactor(delta.y, distancePx)
    }
}

/** Zoom factor for a quick-scale drag of [dyPx] (down is positive): e-fold every [distancePx], so it reverses exactly. */
internal fun quickScaleFactor(dyPx: Float, distancePx: Float): Float = if (distancePx <= 0f) 1f else exp(dyPx / distancePx)

/** Android's default double-tap slop: the second tap must land this close to the first. */
private val DoubleTapSlop = 100.dp

private suspend fun AwaitPointerEventScope.awaitSecondDown(firstUp: PointerInputChange): PointerInputChange? =
    withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
        val minUptime = firstUp.uptimeMillis + viewConfiguration.doubleTapMinTimeMillis
        var change: PointerInputChange
        // A second tap that lands too soon is the same touch bouncing, not a double tap.
        do change = awaitFirstDown() while (change.uptimeMillis < minUptime)
        change
    }

/**
 * Taps on a photo: a single tap calls [onTap] once the double-tap timeout passes; a double tap toggles fit ↔ 2.5×
 * around the tap; double-tap and drag zooms continuously around the tap point (GP's quick scale), clamped to
 * [1, max] with the same 1× detent and haptic as a pinch. Releasing a quick scale keeps its zoom.
 *
 * Put it after [photoZoomGestures] in the modifier chain so it sees each event first and can claim the drag.
 */
@Composable
internal fun Modifier.photoTapGestures(state: PhotoZoomState, reduceMotion: Boolean, key1: Any?, key2: Any?, onTap: () -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    val reduce by rememberUpdatedState(reduceMotion)
    val tap by rememberUpdatedState(onTap)
    return pointerInput(key1, key2) {
        val distancePx = ViewerSpec.QuickScaleDistance.toPx()
        coroutineScope {
            awaitEachGesture {
                awaitFirstDown().consume()
                val firstUp = waitForUpOrCancellation() ?: return@awaitEachGesture
                firstUp.consume()
                val second = awaitSecondDown(firstUp)
                if (second == null) {
                    tap()
                    return@awaitEachGesture
                }
                // Android only pairs taps that land close together; a far second touch is a new gesture.
                if ((second.position - firstUp.position).getDistance() > DoubleTapSlop.toPx()) {
                    tap()
                    return@awaitEachGesture
                }
                second.consume()
                val quick = QuickScaleTracker(viewConfiguration.touchSlop, distancePx)
                val zoom = ZoomGesture(state, density) { haptics.performHapticFeedback(HapticFeedbackType.VirtualKey) }
                while (true) {
                    val event = awaitPointerEvent()
                    // A second finger turns this into a pinch, which photoZoomGestures handles.
                    if (event.changes.count { it.pressed } > 1) return@awaitEachGesture
                    val change = event.changes.firstOrNull { it.id == second.id } ?: return@awaitEachGesture
                    if (!change.pressed) {
                        change.consume()
                        if (!quick.scaling) state.launch(this@coroutineScope) { state.toggle(change.position, reduce) }
                        return@awaitEachGesture
                    }
                    if (!quick.scaling && change.isConsumed) return@awaitEachGesture
                    val factor = quick.move(change.positionChange())
                    if (quick.abandoned) return@awaitEachGesture
                    if (quick.scaling) {
                        if (factor != 1f) {
                            state.stop()
                            zoom.zoom(factor, second.position)
                        }
                        change.consume()
                    }
                }
            }
        }
    }
}
