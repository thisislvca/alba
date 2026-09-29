package dev.mela.app.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Negative offset reveals details; positive offset follows a downward dismissal drag. */
@Stable
internal class ViewerMotion(initialOffset: Float) {
    var offset by mutableFloatStateOf(initialOffset)
        private set
    var dragging by mutableStateOf(false)
    private var settling: Job? = null

    fun moveTo(value: Float) { offset = value }
    fun begin() { settling?.cancel(); dragging = true }
    fun drag(delta: Float, detailsHeight: Float, viewportHeight: Float) {
        offset = (offset + delta).coerceIn(-detailsHeight, viewportHeight * .65f)
    }
    fun settle(scope: CoroutineScope, target: Float) {
        dragging = false
        settling?.cancel()
        settling = scope.launch {
            animate(offset, target, animationSpec = spring(dampingRatio = 1f, stiffness = 400f)) { value, _ -> offset = value }
        }
    }
}
