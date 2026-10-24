package me.lvca.alba.app.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.lvca.alba.app.ui.viewer.reduceGalleryMotion

/** Google Photos' actionable toast, read from GP 7.95 (`bt_animation_duration_toast_translation_ms`, `D/low`). */
internal object SnackbarSpec {
    /** The toast slides in over 300 ms. */
    const val SLIDE_IN_MILLIS = 300

    /** Alba fades a leaving message out quickly, like GP's generic short fade (`photos_animations_fade_out_short`). */
    const val FADE_OUT_MILLIS = 75

    /** Material's own durations for Alba's plain status messages. */
    const val SHORT_MILLIS = 4_000L
    const val LONG_MILLIS = 10_000L

    /** GP's LONG duration, used for "Moved to trash" with Undo. */
    const val UNDO_MILLIS = 5_000L

    /** GP keeps a toast with an action up for at least 10 s while a screen reader is on. */
    const val SCREEN_READER_ACTION_MILLIS = 10_000L

    /** GP's toast shows at most two lines of text. */
    const val UNDO_MAX_LINES = 2
}

/** A message with an Undo action, shown for GP's undo duration rather than one of Material's. */
internal data class UndoSnackbarVisuals(override val message: String, override val actionLabel: String) : SnackbarVisuals {
    override val withDismissAction = false

    /** [AlbaSnackbarHost] times it out; Material must never dismiss it on its own. */
    override val duration = SnackbarDuration.Indefinite
}

/**
 * How long [visuals] stays up, or null to stay until dismissed. [recommended] is Android's accessibility timeout for a
 * base duration and whether the message has a control (it honours the user's "time to take action" setting).
 */
internal fun snackbarTimeoutMillis(visuals: SnackbarVisuals, screenReaderOn: Boolean, recommended: (Long, Boolean) -> Long): Long? {
    val hasAction = visuals.actionLabel != null
    val base = when {
        visuals is UndoSnackbarVisuals -> SnackbarSpec.UNDO_MILLIS
        visuals.duration == SnackbarDuration.Short -> SnackbarSpec.SHORT_MILLIS
        visuals.duration == SnackbarDuration.Long -> SnackbarSpec.LONG_MILLIS
        else -> return null
    }
    val millis = recommended(base, hasAction)
    return if (screenReaderOn && hasAction) maxOf(millis, SnackbarSpec.SCREEN_READER_ACTION_MILLIS) else millis
}

private fun Context.screenReaderOn(): Boolean =
    (getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true

/**
 * Alba's snackbar host. Messages slide up into place over 300 ms like Google Photos' toast and leave with a short
 * fade; the host times them out itself so an undo message gets GP's 5 s (10 s or more with a screen reader).
 */
@Composable
internal fun AlbaSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val current = state.currentSnackbarData
    val accessibility = LocalAccessibilityManager.current
    val context = LocalContext.current
    LaunchedEffect(current) {
        val data = current ?: return@LaunchedEffect
        val millis = snackbarTimeoutMillis(data.visuals, context.screenReaderOn()) { base, hasAction ->
            accessibility?.calculateRecommendedTimeoutMillis(base, containsIcons = true, containsText = true, containsControls = hasAction) ?: base
        } ?: return@LaunchedEffect
        delay(millis)
        data.dismiss()
    }
    val reduceMotion = reduceGalleryMotion()
    AnimatedContent(
        targetState = current,
        modifier = modifier,
        transitionSpec = {
            val enter = if (reduceMotion) fadeIn(tween(SnackbarSpec.FADE_OUT_MILLIS))
            else slideInVertically(tween(SnackbarSpec.SLIDE_IN_MILLIS, easing = LinearOutSlowInEasing)) { it } +
                fadeIn(tween(SnackbarSpec.SLIDE_IN_MILLIS))
            (enter togetherWith fadeOut(tween(SnackbarSpec.FADE_OUT_MILLIS))).using(SizeTransform(clip = true) { _, _ -> snap() })
        },
        contentAlignment = Alignment.BottomCenter,
        label = "snackbar",
    ) { data ->
        if (data != null) Box(Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            dismiss { data.dismiss(); true }
        }) { AlbaSnackbar(data) }
    }
}

@Composable
private fun AlbaSnackbar(data: SnackbarData) {
    val visuals = data.visuals
    val undo = visuals is UndoSnackbarVisuals
    Snackbar(
        modifier = Modifier.testTag("status-snackbar").padding(12.dp),
        action = visuals.actionLabel?.let { label ->
            {
                TextButton(onClick = data::performAction, colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
                    modifier = Modifier.testTag("snackbar-action")) { Text(label) }
            }
        },
    ) {
        Text(visuals.message, maxLines = if (undo) SnackbarSpec.UNDO_MAX_LINES else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
    }
}
