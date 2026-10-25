package me.lvca.alba.app.account

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.BuildConfig
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.R
import me.lvca.alba.app.diagnostics.SentryAlbaDiagnostics
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.UI_WAIT
import org.junit.Rule
import org.junit.Test

class AboutScreenTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }
    private fun label(id: Int) = compose.activity.getString(id)

    @Test fun diagnosticsRespectBuildConfigurationAndTheChoicePersists() {
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("account-button").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("account-button").performClick()
        compose.onNodeWithText(label(R.string.about_title)).performScrollTo().performClick()
        if (BuildConfig.SENTRY_DSN.isBlank()) {
            compose.onNodeWithTag("diagnostics-switch").performScrollTo().assertIsDisplayed().assertIsOff().assertIsNotEnabled()
            compose.onNodeWithText(label(R.string.about_diagnostics_unavailable)).assertIsDisplayed()
            val app = compose.activity.application as AlbaApplication
            org.junit.Assert.assertFalse(app.diagnostics.enabled.value)
            app.diagnostics.setEnabled(true)
            org.junit.Assert.assertFalse(app.diagnostics.enabled.value)
            org.junit.Assert.assertFalse(io.sentry.Sentry.isEnabled())
            // A preference left by a previously configured build cannot enable collection.
            compose.activity.getSharedPreferences("mela_diagnostics", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", true).commit()
            val restored = SentryAlbaDiagnostics(compose.activity)
            restored.initialize()
            org.junit.Assert.assertFalse(restored.enabled.value)
            org.junit.Assert.assertFalse(io.sentry.Sentry.isEnabled())
            return
        }
        compose.onNodeWithTag("diagnostics-switch").performScrollTo().assertIsEnabled().assertIsOn().performClick()
        compose.onNodeWithTag("diagnostics-switch").assertIsOff()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("diagnostics-switch").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("diagnostics-switch").assertIsOn()
    }
}
