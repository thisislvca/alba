package me.lvca.alba.app.testing

import android.view.View
import android.view.inspector.WindowInspector
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Android back, delivered to the topmost app window that handles it: a dialog or sheet, else the activity. */
internal fun ComposeTestRule.pressBack() {
    // Let pending recomposition register or remove back handlers first, as a real key press would wait for.
    waitForIdle()
    runOnUiThread { topDispatcher().onBackPressed() }
    waitForIdle()
}

/**
 * A predictive back gesture from the left edge that reaches [progress], then either commits like lifting the finger
 * past the threshold, or is cancelled like dragging back to the edge.
 */
internal fun ComposeTestRule.backGesture(commit: Boolean, progress: Float = .5f) {
    waitForIdle()
    val dispatcher = runOnUiThread { topDispatcher() }
    runOnUiThread {
        dispatcher.dispatchOnBackStarted(BackEventCompat(8f, 900f, 0f, BackEventCompat.EDGE_LEFT))
        dispatcher.dispatchOnBackProgressed(BackEventCompat(160f, 940f, progress, BackEventCompat.EDGE_LEFT))
    }
    waitForIdle()
    runOnUiThread { if (commit) dispatcher.onBackPressed() else dispatcher.dispatchOnBackCancelled() }
    waitForIdle()
}

/** Robolectric shows no soft keyboard; this only lets pending focus changes settle, as the device helper does. */
internal fun ComposeTestRule.hideKeyboard() = waitForIdle()

private fun topDispatcher(): OnBackPressedDispatcher =
    checkNotNull(appWindows().firstNotNullOfOrNull { it.findViewTreeOnBackPressedDispatcherOwner()?.onBackPressedDispatcher }) {
        "No app window handles back"
    }

/** The app's visible window roots, topmost first: windows are listed in the order they were added. */
private fun appWindows(): List<View> =
    WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow && it.windowVisibility == View.VISIBLE }.asReversed()
