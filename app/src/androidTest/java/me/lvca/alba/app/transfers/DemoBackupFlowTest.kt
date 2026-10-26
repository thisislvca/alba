package me.lvca.alba.app.transfers

import android.Manifest
import androidx.compose.ui.test.*
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.AlbaAppRule
import me.lvca.alba.app.testing.LONG_WAIT
import me.lvca.alba.app.testing.Smoke
import me.lvca.alba.app.testing.UI_WAIT
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Exercises the shipped demo through MainActivity, including its disclosure and permission-free routing. On Robolectric
 * the demo backup's running progress keeps the main thread in a loop that never reaches a JVM safepoint, stalling the
 * whole test JVM, so this journey stays on a device.
 */
class DemoBackupFlowTest {
    @get:Rule val app = AlbaAppRule(onboarded = false)
    private val compose get() = app.compose

    /** The retry button of the one sample row that stopped. */
    private val retry = SemanticsMatcher("demo backup retry") {
        it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith("demo-backup-retry-")
    }

    @Smoke @Test fun sampleBackupAndSyncWorkWithoutAnAppleAccountOrPhotoPermission() {
        val graph = (compose.activity.application as AlbaApplication).graph
        assertNull(graph.accountManager.session.value)
        val permissions = listOf(Manifest.permission.READ_EXTERNAL_STORAGE, "android.permission.READ_MEDIA_IMAGES")
        val before = permissions.map(compose.activity::checkSelfPermission)
        compose.onNodeWithTag("welcome-demo").performClick()
        compose.waitUntil(LONG_WAIT) { compose.onAllNodesWithTag("account-button").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("account-button").performClick()
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("profile-backup").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("profile-backup").performScrollTo().performClick()
        val disclosure = compose.activity.getString(R.string.demo_backup_disclosure)
        compose.onNodeWithTag("backup-start").performScrollTo().performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onAllNodesWithText(disclosure).filterToOne(hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithTag("backup-disclosure-dismiss").performClick()
        compose.onNodeWithTag("backup-start").assertExists()
        compose.onNodeWithTag("backup-start").performClick()
        compose.onNodeWithTag("backup-disclosure-accept").performClick()
        compose.waitUntil(UI_WAIT) {
            compose.onAllNodesWithText(compose.activity.getString(R.string.demo_backup_complete)).fetchSemanticsNodes().size == 3
        }
        compose.onNodeWithTag("transfer-queue").performScrollTo()
        compose.onNodeWithText(compose.activity.getString(R.string.verified_in_icloud)).assertDoesNotExist()
        compose.onNodeWithTag("demo-backup-interrupt").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.demo_backup_interrupted)).performScrollTo().assertIsDisplayed()
        compose.onNode(retry).performScrollTo().performClick()
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithText(compose.activity.getString(R.string.demo_backup_complete)).fetchSemanticsNodes().size == 3 }
        compose.onNodeWithTag("demo-backup-pause-wifi").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.demo_backup_wifi)).performScrollTo().assertIsDisplayed()
        compose.onNode(retry).performScrollTo().performClick()
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithText(compose.activity.getString(R.string.demo_backup_complete)).fetchSemanticsNodes().size == 3 }
        compose.onNodeWithTag("demo-cleanup-preview").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.demo_cleanup_disclosure)).assertIsDisplayed()
        compose.onNodeWithTag("demo-cleanup-confirm").performClick()
        compose.onNodeWithTag("transfer-queue").performScrollTo()
        compose.onAllNodesWithText(compose.activity.getString(R.string.demo_cleanup_complete)).assertCountEquals(3)
        assertEquals(before, permissions.map(compose.activity::checkSelfPermission))
        assertNull(graph.accountManager.session.value)
        compose.onNodeWithTag("demo-sync-refresh").performScrollTo().performClick()
        compose.waitUntil(LONG_WAIT) { compose.onAllNodesWithText("Library refreshed", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("backup-turn-off").performScrollTo().performClick()
        compose.onNodeWithTag("transfer-queue").assertDoesNotExist()
        compose.onNodeWithTag("backup-start").assertIsEnabled()
    }
}
