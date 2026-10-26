package me.lvca.alba.app.testing

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule

/*
 * Device-test timeouts. They bound waits on work Compose's clock does not drive: real threads, Room, the platform.
 *
 * The demo catalog is in the repository and on screen before a test's first Compose call (AlbaAppRule), so no wait
 * covers the initial load any more; that load, queued behind the app's own start-up refresh, was what once needed half
 * a minute. What is left is sized from Android's own limits on the CI emulator (two cores, software rendering), so a
 * wait that runs out points at real slowness rather than a busy emulator:
 *
 * - SHORT_WAIT, 5 s: Android declares an ANR when input goes unanswered for 5 s. A tap whose reaction takes longer is
 *   a bug on any device.
 * - UI_WAIT, 10 s: Android's own activity-launch wait. Recreating an activity, decoding the viewer's photo, or
 *   preparing a video fits in that on the CI emulator.
 * - LONG_WAIT, 15 s: repository, MediaStore and transfer work, which can queue behind the start-up refresh of the
 *   (already seeded) catalog. Half of the old 30 s; still several times the slowest demo transfer.
 *
 * A wait costs time only when a test fails.
 */

/** A reaction inside the app: a tap, a recomposition, a sheet or dialog opening. */
internal const val SHORT_WAIT = 5_000L

/** A screen that decodes, loads or recreates: the viewer, the editor, a new activity, a platform dialog. */
internal const val UI_WAIT = 10_000L

/** Repository, transfer and platform work, which can queue behind other work. */
internal const val LONG_WAIT = 15_000L

/** Navigation, recreation and the viewer transition settle asynchronously; assert only once the node is on screen. */
internal fun ComposeTestRule.waitUntilDisplayed(matcher: SemanticsMatcher): SemanticsNodeInteraction {
    waitUntil(UI_WAIT) { runCatching { onNode(matcher).assertIsDisplayed() }.isSuccess }
    return onNode(matcher)
}
