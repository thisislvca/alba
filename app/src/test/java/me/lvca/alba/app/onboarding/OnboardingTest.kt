package me.lvca.alba.app.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.R
import me.lvca.alba.app.platform.OnboardingPreferences
import me.lvca.alba.app.testing.AlbaAppRule
import me.lvca.alba.app.testing.waitUntilExists
import me.lvca.alba.app.testing.waitUntilSettled
import me.lvca.alba.protocol.account.ICloudAccountState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A fresh install starts at sign-in, with nothing behind it; the demo (debug builds only) leaves it for good. */
@RunWith(AndroidJUnit4::class)
class OnboardingTest {
    @get:Rule val app = AlbaAppRule(onboarded = false)
    private val compose get() = app.compose
    private val graph get() = app.application.graph
    private fun completed() = OnboardingPreferences(app.application).completed

    @Test fun freshInstallStartsAtSignInWithNothingBehindIt() {
        compose.waitUntilExists("sign-in-page")
        compose.onNodeWithTag("sign-in-apple-id").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("sign-in-password").assertIsDisplayed()
        compose.onNodeWithContentDescription(app.string(R.string.back_action)).assertDoesNotExist()
        assertFalse(completed())
        assertEquals(ICloudAccountState.SignedOut, graph.accountManager.state.value)
        assertFalse(OnboardingPreferences(app.application).enabled)
        compose.onNodeWithText(app.string(R.string.demo_badge)).assertDoesNotExist()
        compose.waitUntilSettled { runBlocking { graph.galleryRepository.observeGallery().first().none { it.id.startsWith("fixture:") } } }

        // Nothing in the app takes the back gesture, so it leaves Alba as from any first screen.
        compose.waitForIdle()
        compose.runOnUiThread { assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()) }

        // Recreation stays on sign-in, without starting the attempt over.
        app.recreate()
        compose.onNodeWithTag("sign-in-apple-id").performScrollTo().assertIsDisplayed()
        assertFalse(completed())
    }

    @Test fun theDemoLeavesSignInForGood() {
        compose.waitUntilExists("sign-in-demo")
        compose.onNodeWithTag("sign-in-demo").performClick()
        compose.waitUntilSettled { graph.accountManager.state.value == ICloudAccountState.Demo }
        compose.waitUntilExists("account-button")
        compose.onNodeWithTag("sign-in-page").assertDoesNotExist()
        compose.onNodeWithTag("account-button").assertIsDisplayed()
        assertTrue(completed())

        // A new activity receives no saved Compose state; completion must come from disk.
        compose.activityRule.scenario.close()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("sign-in-page").assertDoesNotExist()
            compose.onNodeWithTag("account-button").assertIsDisplayed()
        }
    }
}
