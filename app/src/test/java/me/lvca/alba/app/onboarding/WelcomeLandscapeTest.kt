package me.lvca.alba.app.onboarding

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.onboarding.WelcomeScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A phone in landscape: the introduction's content scrolls and both choices stay reachable. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w914dp-h411dp-land-xxhdpi")
class WelcomeLandscapeTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun text(id: Int) = context.getString(id)

    @Test fun landscapeKeepsActionsReachableAndContentScrollable() {
        var demo = 0
        compose.setContent { AlbaTheme(darkTheme = false) { WelcomeScreen(onConnect = {}, onDemo = { demo++ }) } }
        compose.onNodeWithText(text(R.string.welcome_continue)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.welcome_optional_body)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.welcome_connect)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.welcome_demo)).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, demo) }
    }
}
