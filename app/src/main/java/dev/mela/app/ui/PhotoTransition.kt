package dev.mela.app.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.provider.Settings

internal const val PHOTO_TRANSITION_MILLIS = 250
private val viewerEaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

@Composable
private fun reduceViewerMotion(): Boolean {
    val context = LocalContext.current
    return Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}

internal data class PhotoTransitionScope(
    val shared: SharedTransitionScope,
    val visibility: AnimatedVisibilityScope,
    val active: Boolean,
    val returningMediaId: String? = null,
)

internal val LocalPhotoTransition = staticCompositionLocalOf<PhotoTransitionScope?> { null }

/** Only the image travels above the screen crossfade; toolbars and tile badges stay in place. */
@Composable
internal fun Modifier.sharedPhoto(id: String?): Modifier {
    val scope = LocalPhotoTransition.current ?: return this
    if (id == null) return this
    val reduceMotion = reduceViewerMotion()
    return with(scope.shared) {
        sharedElement(
            rememberSharedContentState("photo-$id"),
            animatedVisibilityScope = scope.visibility,
            boundsTransform = { _, _ -> if (reduceMotion) snap() else tween(PHOTO_TRANSITION_MILLIS, easing = viewerEaseOut) },
        )
    }
}

@Composable
internal fun Modifier.photoChrome(): Modifier {
    val scope = LocalPhotoTransition.current ?: return this
    val reduceMotion = reduceViewerMotion()
    return with(scope.visibility) {
        animateEnterExit(enter = fadeIn(tween(if (reduceMotion) 80 else 140, delayMillis = if (reduceMotion) 0 else 100)),
            exit = fadeOut(tween(if (reduceMotion) 80 else 90)))
    }
}
