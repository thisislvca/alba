package me.lvca.alba.app.ui.navigation

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** Where a full-screen surface sits while the system back gesture is in progress. */
internal data class BackPose(val scale: Float, val translationX: Float, val translationY: Float, val cornerFraction: Float)

/** Material's full-screen predictive back: the screen shrinks to this scale at full progress. */
internal const val BACK_MIN_SCALE = .9f

/** The corners are fully rounded by this progress, so the shape reads as a card from early in the gesture. */
private const val CORNER_PROGRESS = 1f / 3f

/**
 * Material 3's predictive-back pose for a full-screen surface: it scales toward [BACK_MIN_SCALE], shifts away from the
 * edge the swipe started at while keeping [edgeGapPx] clear of the window edge, follows the finger vertically within
 * the space its own shrinking frees, and rounds its corners early in the gesture.
 *
 * [swipeEdge] is a [BackEventCompat] edge constant; [touchDeltaY] is the finger's travel since the gesture began.
 */
internal fun predictiveBackPose(progress: Float, swipeEdge: Int, touchDeltaY: Float, width: Float, height: Float, edgeGapPx: Float): BackPose {
    val p = progress.coerceIn(0f, 1f)
    val scale = 1f - (1f - BACK_MIN_SCALE) * p
    val maxX = max(0f, (width - width * scale) / 2f - edgeGapPx)
    val direction = when (swipeEdge) {
        BackEventCompat.EDGE_LEFT -> 1f
        BackEventCompat.EDGE_RIGHT -> -1f
        else -> 0f
    }
    val maxY = max(0f, (height - height * scale) / 2f - edgeGapPx)
    val yProgress = if (height > 0f) min(1f, abs(touchDeltaY) / height) else 0f
    return BackPose(
        scale = scale,
        translationX = maxX * direction,
        translationY = maxY * yProgress * sign(touchDeltaY),
        cornerFraction = min(1f, p / CORNER_PROGRESS),
    )
}

/** Where an in-app back gesture is. */
internal enum class BackPhase { IDLE, GESTURE, COMMITTED }

/**
 * One in-app back gesture: which screen it is taking away ([from]) and how far it has gone. The screen keeps its pose
 * after the gesture commits, until its exit transition has finished, so it never jumps back to full size.
 */
@Stable
internal class PredictiveBackState {
    var from: Any? by mutableStateOf(null)
        private set
    var phase by mutableStateOf(BackPhase.IDLE)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var swipeEdge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
        private set
    var touchDeltaY by mutableFloatStateOf(0f)
        private set

    private var startY = 0f
    private var releasedProgress = 0f
    private var releasedDeltaY = 0f

    fun start(from: Any?, event: BackEventCompat) {
        this.from = from
        phase = BackPhase.GESTURE
        swipeEdge = event.swipeEdge
        startY = event.touchY
        update(event)
    }

    fun update(event: BackEventCompat) {
        progress = event.progress
        touchDeltaY = event.touchY - startY
    }

    fun commit() { phase = BackPhase.COMMITTED }

    /** The gesture was cancelled: the pose returns as [fraction] goes from 1 to 0. */
    fun release() {
        phase = BackPhase.IDLE
        releasedProgress = progress
        releasedDeltaY = touchDeltaY
    }

    fun retract(fraction: Float) {
        progress = releasedProgress * fraction
        touchDeltaY = releasedDeltaY * fraction
    }

    fun clear() {
        from = null
        phase = BackPhase.IDLE
        progress = 0f
        touchDeltaY = 0f
    }
}

/** Applies [state]'s pose when this content is the screen the gesture is taking away. */
internal fun Modifier.predictiveBackPose(state: PredictiveBackState, key: Any?): Modifier = graphicsLayer {
    if (state.from == null || state.from != key) return@graphicsLayer
    val pose = predictiveBackPose(state.progress, state.swipeEdge, state.touchDeltaY, size.width, size.height, 8.dp.toPx())
    scaleX = pose.scale
    scaleY = pose.scale
    translationX = pose.translationX
    translationY = pose.translationY
    shape = RoundedCornerShape(28.dp * pose.cornerFraction)
    clip = true
}

/** The share of a gesture-driven transition the gesture itself seeks through: up to [navigationTransform]'s exit fade. */
internal const val GESTURE_SEEK_LIMIT = NavigationMotion.SHORT4.toFloat() / NavigationMotion.MEDIUM2

/** How long a committed back gesture waits for the app state to reach the screen it popped to. */
private const val COMMIT_TIMEOUT_MILLIS = 600L

/**
 * A navigation transition whose pops can follow the system back gesture. It animates to [target] whenever it
 * changes; [PredictiveBackNavigation] seeks it toward the screen back leads to while a gesture is in progress.
 */
@Composable
internal fun <S> rememberNavigationTransition(
    target: S,
    backState: PredictiveBackState,
    reduceMotion: Boolean,
    label: String,
): Pair<SeekableTransitionState<S>, Transition<S>> {
    val state = remember { SeekableTransitionState(target) }
    LaunchedEffect(target, backState.phase) {
        if (backState.phase == BackPhase.GESTURE) return@LaunchedEffect
        // App state can reach the popped screen a few frames after the gesture commits; wait for it before deciding.
        if (backState.phase == BackPhase.COMMITTED && state.currentState == target) delay(COMMIT_TIMEOUT_MILLIS)
        when {
            state.currentState == target && state.targetState == target -> Unit
            reduceMotion -> state.snapTo(target)
            state.currentState == target -> {
                // A cancelled gesture: seek back to the start, then settle where we were.
                backState.release()
                val start = state.fraction
                animate(start, 0f, animationSpec = tween((NavigationMotion.SHORT4 * start).toInt().coerceAtLeast(1),
                    easing = NavigationMotion.Emphasized)) { value, _ ->
                    backState.retract(if (start > 0f) value / start else 0f)
                    launch { state.seekTo(value.coerceIn(0f, 1f)) }
                }
                state.snapTo(target)
            }
            else -> state.animateTo(target)
        }
        backState.clear()
    }
    return state to rememberTransition(state, label)
}

/**
 * Handles back for a screen that pops to [previous]. On Android 14+ the gesture seeks [transition] toward [previous]
 * and poses the screen being left through [backState]; releasing commits with [onBack], cancelling restores the
 * screen. Back button presses and older Android versions simply call [onBack].
 */
@Composable
internal fun <S> PredictiveBackNavigation(
    transition: SeekableTransitionState<S>,
    backState: PredictiveBackState,
    previous: S?,
    key: (S) -> Any?,
    reduceMotion: Boolean,
    enabled: Boolean = true,
    onBack: () -> Unit,
) {
    val latestPrevious by rememberUpdatedState(previous)
    val latestOnBack by rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled = enabled && previous != null) { events ->
        val from = transition.currentState
        val to = latestPrevious
        var started = false
        try {
            events.collect { event ->
                if (reduceMotion || to == null) return@collect
                if (!started) {
                    backState.start(key(from), event)
                    started = true
                } else backState.update(event)
                // The gesture covers the transition up to where the screen being left starts to fade, so that screen
                // stays in view as a card however far the finger travels; committing plays the rest.
                transition.seekTo(event.progress.coerceIn(0f, 1f) * GESTURE_SEEK_LIMIT, to)
            }
            if (started) backState.commit()
            latestOnBack()
        } catch (cancelled: CancellationException) {
            // Hands the seek back to rememberNavigationTransition, which returns to the current screen.
            if (started) backState.release()
            throw cancelled
        }
    }
}
