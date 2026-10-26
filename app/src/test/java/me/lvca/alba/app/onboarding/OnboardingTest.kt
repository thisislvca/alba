package me.lvca.alba.app.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
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
import me.lvca.alba.app.testing.pressBack
import me.lvca.alba.app.testing.waitUntilExists
import me.lvca.alba.app.testing.waitUntilSettled
import me.lvca.alba.protocol.account.ICloudAccountState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The introduction on a fresh install: its pages survive recreation, and both choices leave it for good. */
@RunWith(AndroidJUnit4::class)
class OnboardingTest {
    @get:Rule val app = AlbaAppRule(onboarded = false)
    private val compose get() = app.compose
    private val graph get() = app.application.graph
    private fun completed() = OnboardingPreferences(app.application).completed

    @Test fun introductionRestoresPageAndConnectsWithoutRequestingPermissions() {
        val title = compose.onNodeWithTag("welcome-title")
        val primary = compose.onNodeWithTag("welcome-primary")
        compose.onNodeWithTag("welcome").assertIsDisplayed()
        title.assertTextEquals(app.string(R.string.welcome_title))
        assertFalse(completed())

        // The second page survives recreation, and back returns to the first.
        primary.performClick()
        app.recreate()
        title.assertTextEquals(app.string(R.string.welcome_choices_title))
        assertFalse(completed())
        compose.pressBack()
        title.assertTextEquals(app.string(R.string.welcome_title))
        primary.assertTextEquals(app.string(R.string.welcome_continue)).performClick()

        // Connecting completes the introduction and opens sign-in, outside the demo.
        primary.assertTextEquals(app.string(R.string.welcome_connect)).performClick()
        compose.waitUntilExists("sign-in-apple-id")
        compose.onNodeWithTag("sign-in-apple-id").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("sign-in-password").assertIsDisplayed()
        compose.onNodeWithTag("welcome").assertDoesNotExist()
        assertTrue(completed())
        assertEquals(ICloudAccountState.SignedOut, graph.accountManager.state.value)
        assertFalse(OnboardingPreferences(app.application).enabled)
        compose.onNodeWithText(app.string(R.string.demo_badge)).assertDoesNotExist()
        app.recreate()
        compose.onNodeWithTag("sign-in-apple-id").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("welcome").assertDoesNotExist()
        compose.waitUntilSettled { runBlocking { graph.galleryRepository.observeGallery().first().none { it.id.startsWith("fixture:") } } }

        // Backing out of sign-in returns to the introduction's choices, not to an empty, unconnected library.
        compose.pressBack()
        compose.onNodeWithTag("welcome").assertIsDisplayed()
        primary.assertTextEquals(app.string(R.string.welcome_connect))
        compose.onNodeWithTag("welcome-demo").performClick()
        compose.waitUntilSettled { graph.accountManager.state.value == ICloudAccountState.Demo }
        compose.onNodeWithTag("account-button").assertIsDisplayed()
        assertTrue(completed())

        // A new activity receives no saved Compose state; completion must come from disk.
        compose.activityRule.scenario.close()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("welcome").assertDoesNotExist()
            compose.onNodeWithTag("account-button").assertIsDisplayed()
        }
    }
}
