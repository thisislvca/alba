package me.lvca.alba.app.testing

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule

/*
 * Compose work in JVM tests runs on the test clock, so these limits only bound work the app does on real background
 * threads: Room, the demo catalog, preview decoding. That normally takes milliseconds; a limit is reached only when a
 * test fails. The names match the device tests' helpers so a test reads the same on either side.
 */

/** A reaction inside the app: a tap, a recomposition, a sheet or dialog opening. */
internal const val SHORT_WAIT = 5_000L

/** A screen that decodes, loads or recreates. */
internal const val UI_WAIT = 10_000L

/** Repository and transfer work. */
internal const val LONG_WAIT = 10_000L

/**
 * Waits for [condition] on state outside the UI, such as the repository after an edit. The app's coroutines resume on
 * the main looper, which Robolectric runs only while Compose synchronizes, so each check synchronizes first; a plain
 * `waitUntil` on such a condition would never let them finish.
 */
internal fun ComposeTestRule.waitUntilSettled(timeoutMillis: Long = LONG_WAIT, condition: () -> Boolean) =
    waitUntil(timeoutMillis) {
        waitForIdle()
        condition()
    }

/** Navigation, recreation and the viewer transition settle asynchronously; assert only once the node is on screen. */
internal fun ComposeTestRule.waitUntilDisplayed(matcher: SemanticsMatcher): SemanticsNodeInteraction {
    waitUntil(LONG_WAIT) { runCatching { onNode(matcher).assertIsDisplayed() }.isSuccess }
    return onNode(matcher)
}

internal fun ComposeTestRule.waitUntilExists(tag: String) =
    waitUntil(UI_WAIT) { onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

internal fun ComposeTestRule.waitUntilGone(tag: String) =
    waitUntil(UI_WAIT) { onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }
