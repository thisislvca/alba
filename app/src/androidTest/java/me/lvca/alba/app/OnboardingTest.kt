package me.lvca.alba.app

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OnboardingTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule(completed = false)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = instrumentation.targetContext.getExternalFilesDir("onboarding")!!.apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun introductionRestoresPageAndConnectsWithoutRequestingPermissions() {
        compose.onNodeWithTag("welcome").assertIsDisplayed()
        compose.onNodeWithText("Your iCloud photos.\nOn Android.").assertIsDisplayed()
        assertFalse(OnboardingPreferences(compose.activity).completed)
        capture("01-welcome")
        compose.onNodeWithText("Continue").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("You’re in control.").assertIsDisplayed()
        assertFalse(OnboardingPreferences(compose.activity).completed)
        capture("02-choices")
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("Continue").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Connect iCloud").performClick()
        compose.waitUntil(CI_WAIT_MS) { compose.onAllNodesWithText("Sign in to iCloud").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Sign in to iCloud").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("welcome").assertDoesNotExist()
        assertTrue(OnboardingPreferences(compose.activity).completed)
        val graph = (compose.activity.application as AlbaApplication).graph
        assertEquals(me.lvca.alba.protocol.account.ICloudAccountState.SignedOut, graph.accountManager.state.value)
        assertFalse(OnboardingPreferences(compose.activity).enabled)
        compose.onNodeWithText("Password").assertIsDisplayed()
        capture("03-connect-sign-in")
        compose.onNodeWithText("Demo", substring = true).assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Sign in to iCloud").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("welcome").assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("Demo", substring = true).assertDoesNotExist()
        compose.waitUntil(CI_WAIT_MS) { kotlinx.coroutines.runBlocking { graph.galleryRepository.observeGallery().first().none { it.id.startsWith("fixture:") } } }
        capture("04-signed-out-library")
    }

    @Test fun demoFromFirstPagePersistsAcrossANewActivity() {
        compose.onNodeWithText("Try the demo").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("account-button").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(OnboardingPreferences(compose.activity).completed)
        // A new Activity receives no saved Compose state; completion must come from disk.
        compose.activityRule.scenario.close()
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("welcome").assertDoesNotExist()
            compose.onNodeWithTag("account-button").assertIsDisplayed()
        }
    }

    @Test fun landscapeKeepsActionsReachableAndContentScrollable() {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        try {
            compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithText("Continue").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.welcome_optional_body)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Connect iCloud").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Try the demo").performScrollTo().assertIsDisplayed()
            capture("03-landscape")
            compose.onNodeWithText("Try the demo").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("account-button").fetchSemanticsNodes().isNotEmpty() }
        } finally { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}
