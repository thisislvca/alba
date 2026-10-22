package me.lvca.alba.app.ui.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset

/** Material 3 motion tokens used by screen-to-screen navigation. */
internal object NavigationMotion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Durations from the Material 3 scale: short2, short4, medium1 and medium2. */
    const val SHORT2 = 100
    const val SHORT4 = 200
    const val MEDIUM1 = 250
    const val MEDIUM2 = 300

    /** Shared-axis travel for screens of the same hierarchy. */
    const val SHARED_AXIS_DP = 30

    /** The standard motion scheme's fast spatial spring, for small components that move or resize. */
    fun <T> fastSpatial(): SpringSpec<T> = spring(dampingRatio = .9f, stiffness = 1400f)

    /** The standard motion scheme's fast effects spring, for colour and opacity. */
    fun <T> fastEffects(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 3800f)
}

/** Which way a navigation goes, which picks the Material pattern for it. */
internal enum class NavigationDirection { FORWARD, BACK, LATERAL }

/**
 * Material shared axis X for going into and out of a hierarchy, and fade through for switching between unrelated
 * places such as bottom-navigation destinations.
 *
 * [gesture] is true when the system back gesture drives the transition, whatever the direction: the screen being left
 * then takes its pose from the gesture (see [predictiveBackPose]) and only fades once the gesture commits, while the
 * screen back leads to comes in underneath it.
 */
internal fun navigationTransform(
    direction: NavigationDirection,
    slidePx: Int,
    gesture: Boolean = false,
    reduceMotion: Boolean = false,
): ContentTransform {
    if (reduceMotion) {
        return fadeIn(tween(NavigationMotion.SHORT2)).togetherWith(fadeOut(tween(NavigationMotion.SHORT2)))
    }
    val outgoingFade = tween<Float>(OUTGOING_FADE, easing = LinearEasing)
    val incomingFade = tween<Float>(NavigationMotion.MEDIUM2 - OUTGOING_FADE, delayMillis = OUTGOING_FADE, easing = LinearEasing)
    val slide = tween<IntOffset>(NavigationMotion.MEDIUM2, easing = NavigationMotion.Emphasized)
    return when {
        gesture -> {
            val enter = slideInHorizontally(slide) { -slidePx } + fadeIn(tween(NavigationMotion.MEDIUM2, easing = LinearEasing))
            val exit = fadeOut(tween(NavigationMotion.SHORT2, delayMillis = NavigationMotion.SHORT4, easing = LinearEasing))
            (enter togetherWith exit).apply { targetContentZIndex = -1f }
        }
        direction == NavigationDirection.LATERAL -> fadeIn(incomingFade).togetherWith(fadeOut(outgoingFade))
        direction == NavigationDirection.FORWARD ->
            (slideInHorizontally(slide) { slidePx } + fadeIn(incomingFade))
                .togetherWith(slideOutHorizontally(slide) { -slidePx } + fadeOut(outgoingFade))
        else -> (slideInHorizontally(slide) { -slidePx } + fadeIn(incomingFade))
            .togetherWith(slideOutHorizontally(slide) { slidePx } + fadeOut(outgoingFade))
            .apply { targetContentZIndex = -1f }
    }
}

/** Fade through's outgoing share of the transition, as in Material's 300 ms pattern. */
private const val OUTGOING_FADE = 90
