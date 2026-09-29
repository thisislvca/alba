package dev.mela.app.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

internal const val PHOTO_TRANSITION_MILLIS = 360

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
    return with(scope.shared) {
        sharedElement(
            rememberSharedContentState("photo-$id"),
            animatedVisibilityScope = scope.visibility,
            boundsTransform = { _, _ -> tween(PHOTO_TRANSITION_MILLIS, easing = FastOutSlowInEasing) },
        )
    }
}

@Composable
internal fun Modifier.photoChrome(): Modifier {
    val scope = LocalPhotoTransition.current ?: return this
    return with(scope.visibility) {
        animateEnterExit(enter = fadeIn(tween(140, delayMillis = 160)), exit = fadeOut(tween(90)))
    }
}
