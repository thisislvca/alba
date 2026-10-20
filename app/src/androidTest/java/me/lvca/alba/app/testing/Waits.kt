package me.lvca.alba.app.testing

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Every device-test timeout scales from this base. CI emulators use software rendering on two cores, and repository
 * work can queue behind a catalog refresh, so waits are generous; they only cost time when a test fails.
 */
private const val WAIT_BASE_MS = 5_000L

/** A reaction inside the app: a tap, a recomposition, a sheet or dialog opening. */
internal const val SHORT_WAIT = WAIT_BASE_MS

/** A screen that decodes, loads or recreates: the viewer, the editor, a new activity, a platform dialog. */
internal const val UI_WAIT = WAIT_BASE_MS * 3

/** Repository, transfer and platform work, which can queue behind other work. */
internal const val LONG_WAIT = WAIT_BASE_MS * 6

/** Navigation, recreation and the viewer transition settle asynchronously; assert only once the node is on screen. */
internal fun ComposeTestRule.waitUntilDisplayed(matcher: SemanticsMatcher): SemanticsNodeInteraction {
    waitUntil(LONG_WAIT) { runCatching { onNode(matcher).assertIsDisplayed() }.isSuccess }
    return onNode(matcher)
}
