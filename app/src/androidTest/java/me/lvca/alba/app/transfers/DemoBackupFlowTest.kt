package me.lvca.alba.app.transfers

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.LONG_WAIT
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.UI_WAIT
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the shipped demo through MainActivity, including its disclosure and permission-free routing. */
class DemoBackupFlowTest {
    @get:Rule(order = 1) val onboarding = OnboardingRule(completed = false)
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 3) val failureCapture = FailureCapture { compose }

    @Test fun sampleBackupAndSyncWorkWithoutAnAppleAccountOrPhotoPermission() {
        val graph = (compose.activity.application as AlbaApplication).graph
        assertNull(graph.accountManager.session.value)
        val permissions = listOf(Manifest.permission.READ_EXTERNAL_STORAGE, "android.permission.READ_MEDIA_IMAGES")
        val before = permissions.map(compose.activity::checkSelfPermission)
        compose.onNodeWithText(compose.activity.getString(R.string.welcome_demo)).performClick()
        compose.waitUntil(LONG_WAIT) { compose.onAllNodesWithTag("account-button").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("account-button").performClick()
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("profile-backup").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("profile-backup").performScrollTo().performClick()
        val start = compose.activity.getString(R.string.demo_backup_start)
        val disclosure = compose.activity.getString(R.string.demo_backup_disclosure)
        compose.onNodeWithText(start).performScrollTo().performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onAllNodesWithText(disclosure).filterToOne(hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.not_now)).performClick()
        compose.onNodeWithText(start).assertExists()
        compose.onNodeWithText(start).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.welcome_continue)).performClick()
        compose.waitUntil(UI_WAIT) {
            compose.onAllNodesWithText(compose.activity.getString(R.string.demo_backup_complete)).fetchSemanticsNodes().size == 3
        }
        compose.onNodeWithTag("transfer-queue").performScrollTo()
        compose.onNodeWithText(compose.activity.getString(R.string.verified_in_icloud)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.demo_backup_interrupt)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.demo_backup_interrupted)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.demo_backup_retry)).performScrollTo().performClick()
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithText(compose.activity.getString(R.string.demo_backup_complete)).fetchSemanticsNodes().size == 3 }
        compose.onNodeWithText(compose.activity.getString(R.string.demo_backup_pause_wifi)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.demo_backup_wifi)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.demo_backup_retry)).performScrollTo().performClick()
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithText(compose.activity.getString(R.string.demo_backup_complete)).fetchSemanticsNodes().size == 3 }
        compose.onNodeWithText(compose.activity.getString(R.string.demo_cleanup_preview)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.demo_cleanup_disclosure)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.welcome_continue)).performClick()
        compose.onNodeWithTag("transfer-queue").performScrollTo()
        compose.onAllNodesWithText(compose.activity.getString(R.string.demo_cleanup_complete)).assertCountEquals(3)
        assertEquals(before, permissions.map(compose.activity::checkSelfPermission))
        assertNull(graph.accountManager.session.value)
        compose.onNodeWithText(compose.activity.getString(R.string.demo_sync_refresh)).performScrollTo().performClick()
        compose.waitUntil(LONG_WAIT) { compose.onAllNodesWithText("Library refreshed", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(compose.activity.getString(R.string.turn_off_automatic_backup)).performScrollTo().performClick()
        compose.onNodeWithTag("transfer-queue").assertDoesNotExist()
        compose.onNodeWithText(start).assertIsEnabled()
    }
}
