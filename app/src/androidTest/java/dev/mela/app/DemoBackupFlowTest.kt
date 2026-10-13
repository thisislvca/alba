package dev.mela.app

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the shipped demo through MainActivity, including its disclosure and permission-free routing. */
class DemoBackupFlowTest {
    @get:Rule(order = 1) val onboarding = OnboardingRule(completed = false)
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun sampleBackupAndSyncWorkWithoutAnAppleAccountOrPhotoPermission() {
        val graph = (compose.activity.application as MelaApplication).graph
        assertNull(graph.accountManager.session.value)
        val permissions = listOf(Manifest.permission.READ_EXTERNAL_STORAGE, "android.permission.READ_MEDIA_IMAGES")
        val before = permissions.map(compose.activity::checkSelfPermission)
        compose.onNodeWithText(compose.activity.getString(R.string.welcome_demo)).performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("account-button").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("account-button").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("profile-backup").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("profile-backup").performScrollTo().performClick()
        val start = compose.activity.getString(R.string.demo_backup_start)
        val disclosure = compose.activity.getString(R.string.demo_backup_disclosure)
        compose.onNodeWithText(start).performScrollTo().performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onAllNodesWithText(disclosure).filterToOne(hasAnyAncestor(isDialog())).assertIsDisplayed()
        capture("demo-backup-disclosure")
        compose.onNodeWithText(compose.activity.getString(R.string.not_now)).performClick()
        compose.onNodeWithText(start).assertExists()
        compose.onNodeWithText(start).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.welcome_continue)).performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(compose.activity.getString(R.string.demo_backup_complete)).fetchSemanticsNodes().size == 3
        }
        compose.onNodeWithTag("transfer-queue").performScrollTo()
        capture("demo-backup-complete")
        compose.onNodeWithText(compose.activity.getString(R.string.verified_in_icloud)).assertDoesNotExist()
        assertEquals(before, permissions.map(compose.activity::checkSelfPermission))
        assertNull(graph.accountManager.session.value)
        compose.onNodeWithText(compose.activity.getString(R.string.demo_sync_refresh)).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Library refreshed", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(compose.activity.getString(R.string.turn_off_automatic_backup)).performScrollTo().performClick()
        compose.onNodeWithTag("transfer-queue").assertDoesNotExist()
        compose.onNodeWithText(start).assertIsEnabled()
        capture("demo-backup-ready")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: compose.activity.getExternalFilesDir("demo-review")!!).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
