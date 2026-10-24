package me.lvca.alba.app.ui.viewer

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign

/**
 * Every tunable number of the full-screen viewer. Each one cites the Google Photos 7.95
 * behaviour it reproduces (values read from the decompiled app; no Google code is used).
 */
internal object ViewerSpec {
    /** GP opens and closes the viewer with one 200 ms driver: geometry FastOutSlowIn, chrome linear. */
    const val TRANSITION_MILLIS = 200
    val TransitionEasing: Easing = FastOutSlowInEasing

    /** GP swipe-down reaches its full effect (scale 0.85, transparent backdrop) after 60dp of travel. */
    val DismissFullEffect = 60.dp
    const val DISMISS_MIN_SCALE = .85f

    /** GP dismisses past half the full effect (~30dp of travel); there is no velocity test. */
    const val DISMISS_COMMIT_PROGRESS = .5f

    /** GP settles both release outcomes with the same 200 ms FastOutSlowIn driver, not a spring. */
    const val DISMISS_SETTLE_MILLIS = 200

    /**
     * GP pinch-in at fit scale closes the viewer: the backdrop fades as 1 − clamp((1 − scale) / 0.35) and a release
     * at or past half of that (scale ≤ 0.825) closes, otherwise the photo returns to fit.
     */
    const val PINCH_CLOSE_FULL_EFFECT = .35f
    const val PINCH_CLOSE_COMMIT_PROGRESS = .5f

    /** Alba: a pinch to close never shrinks the photo below this, so it stays recognisable under the fingers. */
    const val PINCH_CLOSE_MIN_SCALE = .25f

    /** GP starts details within ±30° of straight up: tan(30°). Dismissal takes anything within 45° of down. */
    const val DETAILS_START_MAX_SLOPE = .577f

    /** GP: below 10 px/s the position (more than half open) decides, otherwise the direction of travel. */
    const val DETAILS_VELOCITY_DEADZONE = 10f

    /** GP opens a slowly released sheet that is more than half open. */
    const val DETAILS_OPEN_POSITION = .5f

    /** Alba bounds for the sheet on unusual aspect ratios (tablets, split screen). */
    const val DETAILS_MIN_FRACTION = .3f
    const val DETAILS_MAX_FRACTION = .85f

    /** GP snaps the details sheet in 225 ms with LinearOutSlowIn. */
    const val DETAILS_SNAP_MILLIS = 225
    val DetailsEasing: Easing = LinearOutSlowInEasing

    /** GP expanded details leave a 16:9 media band under the status bar in portrait... */
    const val DETAILS_MEDIA_BAND = 9f / 16f

    /** ...and take two thirds of the height in landscape. */
    const val DETAILS_LANDSCAPE_FRACTION = 2f / 3f

    /** GP `photos_pager_page_margin`. */
    val PageSpacing = 24.dp

    /**
     * GP double tap toggles between fit and 2.5×. A zoomed photo clamps hard at its edges and
     * never hands off to the pager, so paging only happens at fit scale.
     */
    const val DOUBLE_TAP_ZOOM = 2.5f

    /**
     * GP's double-tap-and-drag uses the platform quick scale. Alba zooms exponentially with the drag instead, so a
     * drag back up always returns to the same zoom: e-fold every 160dp, about 2.5× after 150dp.
     */
    val QuickScaleDistance = 160.dp

    /** GP animates double-tap zoom over 250 ms, FastOutSlowIn on both scale and centre. */
    const val ZOOM_MILLIS = 250

    /** GP max zoom: 150 source pixels per screen inch, never below 2× or the fill scale. */
    const val MAX_ZOOM_SOURCE_PPI = 150f
    const val MIN_MAX_ZOOM = 2f

    /**
     * GP falls back to 12× without dimensions. Alba decodes at most twice the screen and has no
     * tiled decoding, so it stops at 4×, where the photo still looks like a photo.
     */
    const val UNKNOWN_MAX_ZOOM = 4f

    /** GP 1× detent: snaps when the fitted long side is within +16dp/−28dp of fit, releases 8dp beyond. */
    val DetentAbove = 16.dp
    val DetentBelow = 28.dp
    val DetentRelease = 8.dp

    /** GP fades chrome in and out over 200 ms, linear, starting from its current alpha. */
    const val CHROME_FADE_MILLIS = 200

    /** GP predictive back: scale 1 − 0.35p, shift width × 0.35p × 0.35 away from the edge. */
    const val BACK_MIN_SCALE = .65f
    const val BACK_SHIFT = .35f * .35f

    /** GP predictive back: the photo follows the touch Y delta as sign·|dy|^0.8. */
    const val BACK_FOLLOW_EXPONENT = .8f

    /** GP predictive back: chrome and backdrop alpha max(0.1, 1 − 2p). */
    const val BACK_MIN_ALPHA = .1f
}

/** Where the current photo is drawn and how opaque the canvas and chrome around it are. */
@Immutable
internal data class ViewerPose(val translation: Offset, val scale: Float, val backdrop: Float) {
    companion object {
        val Rest = ViewerPose(Offset.Zero, 1f, 1f)
    }
}

internal enum class ViewerDrag { DISMISS, DETAILS }

internal fun pinchCloseProgress(scale: Float): Float = ((1f - scale) / ViewerSpec.PINCH_CLOSE_FULL_EFFECT).coerceIn(0f, 1f)

internal fun pinchClosePose(scale: Float, translation: Offset): ViewerPose =
    ViewerPose(translation, scale, 1f - pinchCloseProgress(scale))

/** Whether a pinch to close released at [scale] closes the viewer (GP: once the backdrop is at most half opaque). */
internal fun shouldClosePinch(scale: Float): Boolean = pinchCloseProgress(scale) >= ViewerSpec.PINCH_CLOSE_COMMIT_PROGRESS

/** Decides what a single-finger drag past touch slop means, from its travel so far. */
internal fun classifyViewerDrag(travel: Offset, detailsOpen: Boolean): ViewerDrag? {
    val horizontal = abs(travel.x)
    val vertical = abs(travel.y)
    return when {
        detailsOpen -> ViewerDrag.DETAILS.takeIf { vertical > horizontal }
        travel.y > 0f && vertical > horizontal -> ViewerDrag.DISMISS
        travel.y < 0f && horizontal <= vertical * ViewerSpec.DETAILS_START_MAX_SLOPE -> ViewerDrag.DETAILS
        else -> null
    }
}

internal fun dismissProgress(travel: Offset, fullEffectPx: Float): Float =
    if (fullEffectPx <= 0f) 1f else (travel.getDistance() / fullEffectPx).coerceIn(0f, 1f)

internal fun dismissPose(travel: Offset, fullEffectPx: Float): ViewerPose {
    val progress = dismissProgress(travel, fullEffectPx)
    return ViewerPose(travel, 1f - (1f - ViewerSpec.DISMISS_MIN_SCALE) * progress, 1f - progress)
}

internal fun shouldDismiss(travel: Offset, fullEffectPx: Float): Boolean =
    dismissProgress(travel, fullEffectPx) > ViewerSpec.DISMISS_COMMIT_PROGRESS

/** [velocityY] is in px/s, positive downward. [progress] is 0 collapsed to 1 expanded. */
internal fun detailsOpenAfterRelease(progress: Float, velocityY: Float): Boolean =
    if (abs(velocityY) < ViewerSpec.DETAILS_VELOCITY_DEADZONE) progress > ViewerSpec.DETAILS_OPEN_POSITION else velocityY < 0f

/** Height of the expanded details sheet, leaving GP's 16:9 media band in portrait. */
internal fun detailsSheetHeight(widthPx: Float, heightPx: Float, statusBarPx: Float): Float {
    val height = if (widthPx > heightPx) heightPx * ViewerSpec.DETAILS_LANDSCAPE_FRACTION
    else heightPx - statusBarPx - widthPx * ViewerSpec.DETAILS_MEDIA_BAND
    return height.coerceIn(heightPx * ViewerSpec.DETAILS_MIN_FRACTION, heightPx * ViewerSpec.DETAILS_MAX_FRACTION)
}

/** [edgeSign] is +1 for a swipe from the left edge (the photo moves right), −1 from the right. */
internal fun predictiveBackPose(progress: Float, edgeSign: Float, touchDy: Float, widthPx: Float): ViewerPose {
    val p = progress.coerceIn(0f, 1f)
    return ViewerPose(
        translation = Offset(edgeSign * widthPx * ViewerSpec.BACK_SHIFT * p, sign(touchDy) * abs(touchDy).pow(ViewerSpec.BACK_FOLLOW_EXPONENT)),
        scale = 1f - (1f - ViewerSpec.BACK_MIN_SCALE) * p,
        backdrop = max(ViewerSpec.BACK_MIN_ALPHA, 1f - 2f * p),
    )
}

/**
 * Transient viewer gesture state: the details sheet, the swipe-down dismissal and predictive back.
 * Details are kept as a fraction of the sheet height, so a resize or rotation never drops an
 * in-flight drag or re-opens the sheet at a stale height.
 */
@Stable
internal class ViewerMotion(detailsOpen: Boolean) {
    /** 0 is collapsed, 1 is fully expanded. */
    var details by mutableFloatStateOf(if (detailsOpen) 1f else 0f)
        private set

    /** Finger travel of a swipe-down dismissal, or a pinch to close's offset; the photo follows it in X and Y. */
    var travel by mutableStateOf(Offset.Zero)
        private set

    /** The photo's scale during a pinch to close; 1 otherwise. */
    var pinchScale by mutableFloatStateOf(1f)
        private set
    private var pinching by mutableStateOf(false)
    var dragging by mutableStateOf(false)
    private var backProgress by mutableFloatStateOf(0f)
    private var backEdge by mutableFloatStateOf(1f)
    private var backDy by mutableFloatStateOf(0f)
    private var settling: Job? = null

    fun pose(fullEffectPx: Float, widthPx: Float): ViewerPose = when {
        backProgress > 0f -> predictiveBackPose(backProgress, backEdge, backDy, widthPx)
        pinching -> pinchClosePose(pinchScale, travel)
        travel != Offset.Zero -> dismissPose(travel, fullEffectPx)
        else -> ViewerPose.Rest
    }

    fun begin() {
        settling?.cancel()
        dragging = true
    }

    fun dragDismiss(delta: Offset) {
        travel += delta
    }

    /** A pinch to close: the photo is drawn at [scale] (below 1), offset by [translation]. */
    fun pinchClose(scale: Float, translation: Offset) {
        pinching = true
        pinchScale = scale
        travel = translation
    }

    fun dragDetails(deltaYPx: Float, sheetHeightPx: Float) {
        if (sheetHeightPx > 0f) details = (details - deltaYPx / sheetHeightPx).coerceIn(0f, 1f)
    }

    fun moveDetails(progress: Float) {
        details = progress.coerceIn(0f, 1f)
    }

    fun moveBack(progress: Float, edgeSign: Float, touchDy: Float) {
        backProgress = progress
        backEdge = edgeSign
        backDy = touchDy
    }

    fun settleDetails(scope: CoroutineScope, open: Boolean, reduceMotion: Boolean) = settle(scope) {
        val target = if (open) 1f else 0f
        if (reduceMotion) details = target
        else animate(details, target, animationSpec = tween(ViewerSpec.DETAILS_SNAP_MILLIS, easing = ViewerSpec.DetailsEasing)) { value, _ -> details = value }
    }

    /** Returns a swipe-down or a pinch to close to rest, with GP's 200 ms FastOutSlowIn driver. */
    fun settleDismiss(scope: CoroutineScope, reduceMotion: Boolean) = settle(scope) {
        try {
            if (!reduceMotion) {
                val fromTravel = travel
                val fromScale = pinchScale
                animate(0f, 1f, animationSpec = tween(ViewerSpec.DISMISS_SETTLE_MILLIS, easing = FastOutSlowInEasing)) { t, _ ->
                    travel = lerp(fromTravel, Offset.Zero, t)
                    pinchScale = lerp(fromScale, 1f, t)
                }
            }
            travel = Offset.Zero
        } finally {
            // A swipe interrupted mid-settle continues from where it is; a pinch restarts from fit, so snap it there.
            if (pinching) {
                travel = Offset.Zero
                pinchScale = 1f
                pinching = false
            }
        }
    }

    fun settleBack(scope: CoroutineScope, reduceMotion: Boolean) = settle(scope) {
        val progress = backProgress
        val dy = backDy
        if (!reduceMotion) {
            animate(1f, 0f, animationSpec = tween(ViewerSpec.DISMISS_SETTLE_MILLIS, easing = FastOutSlowInEasing)) { left, _ ->
                backProgress = progress * left
                backDy = dy * left
            }
        }
        backProgress = 0f
        backDy = 0f
    }

    private fun settle(scope: CoroutineScope, block: suspend () -> Unit) {
        dragging = false
        settling?.cancel()
        settling = scope.launch { block() }
    }
}
