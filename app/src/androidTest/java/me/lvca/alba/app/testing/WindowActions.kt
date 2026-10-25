package me.lvca.alba.app.testing

import android.view.View
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import androidx.activity.BackEventCompat
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/*
 * Espresso's pressBack and closeSoftKeyboard first wait up to ten seconds for the app's window to take input focus.
 * On CI emulators focus can trail recreation or a window change by longer than that, and a system dialog can take it
 * outright, so these act on the app's own windows directly.
 */

/** Android back, delivered to the topmost app window that handles it: a dialog or sheet, else the activity. */
internal fun ComposeTestRule.pressBack() {
    // Let pending recomposition register or remove back handlers first, as a real key press would wait for.
    waitForIdle()
    runOnUiThread {
        val dispatcher = appWindows().firstNotNullOfOrNull { it.findViewTreeOnBackPressedDispatcherOwner()?.onBackPressedDispatcher }
        checkNotNull(dispatcher) { "No app window handles back" }.onBackPressed()
    }
    waitForIdle()
}

/**
 * An Android 14+ predictive back gesture from the left edge that reaches [progress], then either commits like
 * lifting the finger past the threshold, or is cancelled like dragging back to the edge.
 */
internal fun ComposeTestRule.backGesture(commit: Boolean, progress: Float = .5f) {
    waitForIdle()
    val dispatcher = runOnUiThread {
        checkNotNull(appWindows().firstNotNullOfOrNull { it.findViewTreeOnBackPressedDispatcherOwner()?.onBackPressedDispatcher }) {
            "No app window handles back"
        }
    }
    runOnUiThread {
        dispatcher.dispatchOnBackStarted(BackEventCompat(8f, 900f, 0f, BackEventCompat.EDGE_LEFT))
        dispatcher.dispatchOnBackProgressed(BackEventCompat(160f, 940f, progress, BackEventCompat.EDGE_LEFT))
    }
    waitForIdle()
    runOnUiThread { if (commit) dispatcher.onBackPressed() else dispatcher.dispatchOnBackCancelled() }
    waitForIdle()
}

/** Hides the soft keyboard and waits until no app window shows it. */
internal fun ComposeTestRule.hideKeyboard() {
    waitForIdle()
    // Ask before the keyboard shows, too: a text field that just took focus may still be opening it, and the input
    // method handles the two requests in order. Ask again while any window still shows it.
    runOnUiThread { appWindows().firstOrNull(View::hasWindowFocus)?.let(::hideKeyboard) }
    waitUntil(SHORT_WAIT) { runOnUiThread { appWindows().filter(::showsKeyboard).onEach(::hideKeyboard).isEmpty() } }
}

private fun showsKeyboard(window: View) = ViewCompat.getRootWindowInsets(window)?.isVisible(WindowInsetsCompat.Type.ime()) == true

private fun hideKeyboard(window: View) {
    window.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(window.windowToken, 0)
}

/** The app's visible window roots, topmost first: windows are listed in the order they were added. */
private fun appWindows(): List<View> =
    WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow && it.windowVisibility == View.VISIBLE }.asReversed()
