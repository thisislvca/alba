package me.lvca.alba.app.ui.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CancellationException

/**
 * Single-finger vertical drags on the viewer: swipe down to dismiss (the photo follows the finger
 * freely) and swipe up for details. Horizontal drags are left to the pager, and anything a child
 * consumed first (a zoomed pan, the video seek bar) cancels the claim.
 */
@Composable
internal fun Modifier.viewerDrag(
    enabled: Boolean,
    detailsOpen: () -> Boolean,
    onStart: (ViewerDrag) -> Unit,
    onDrag: (ViewerDrag, Offset) -> Unit,
    onStop: (ViewerDrag, Velocity) -> Unit,
): Modifier {
    val isDetailsOpen by rememberUpdatedState(detailsOpen)
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val stop by rememberUpdatedState(onStop)
    if (!enabled) return this
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val tracker = VelocityTracker().apply { addPointerInputChange(down) }
            var travel = Offset.Zero
            var mode: ViewerDrag? = null
            val slop = viewConfiguration.touchSlop
            while (mode == null) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1) return@awaitEachGesture
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed || change.isConsumed) return@awaitEachGesture
                travel += change.positionChange()
                tracker.addPointerInputChange(change)
                val distance = travel.getDistance()
                if (distance > slop) {
                    val claimed = classifyViewerDrag(travel, isDetailsOpen()) ?: return@awaitEachGesture
                    change.consume()
                    mode = claimed
                    start(claimed)
                    // Follow the finger from where the slop ended so the photo doesn't jump.
                    drag(claimed, travel * (1f - slop / distance))
                }
            }
            val claimed = checkNotNull(mode)
            var velocity = Velocity.Zero
            try {
                var following = true
                while (following) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    when {
                        change == null || !change.pressed -> {
                            velocity = tracker.calculateVelocity()
                            following = false
                        }
                        // A child took over (for example a second finger started a pinch): settle without velocity.
                        change.isConsumed -> following = false
                        else -> {
                            tracker.addPointerInputChange(change)
                            drag(claimed, change.positionChange())
                            change.consume()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                stop(claimed, Velocity.Zero)
                throw cancelled
            }
            stop(claimed, velocity)
        }
    }
}
