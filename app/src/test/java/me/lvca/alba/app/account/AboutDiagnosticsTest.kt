package me.lvca.alba.app.account

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.sentry.Sentry
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.BuildConfig
import me.lvca.alba.app.DiagnosticsConsent
import me.lvca.alba.app.R
import me.lvca.alba.app.diagnostics.SentryAlbaDiagnostics
import me.lvca.alba.app.ui.account.AboutScreen
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic reporting is opt-out only in a build configured for it, and can never be switched on without one. */
@RunWith(AndroidJUnit4::class)
class AboutDiagnosticsTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<AlbaApplication>()

    @Test fun aBuildWithoutDiagnosticsShowsTheSwitchOffAndDisabled() {
        compose.setContent {
            AlbaTheme(darkTheme = false) { AboutScreen(DiagnosticsConsent(available = false), { error("The switch is disabled") }, {}) }
        }
        compose.onNodeWithTag("diagnostics-switch").performScrollTo().assertIsDisplayed().assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText(app.getString(R.string.about_diagnostics_unavailable)).assertIsDisplayed()
    }

    @Test fun aConfiguredBuildLetsTheUserTurnDiagnosticsOff() {
        val choices = mutableListOf<Boolean>()
        compose.setContent {
            AlbaTheme(darkTheme = false) { AboutScreen(DiagnosticsConsent(available = true, enabled = true), { choices += it }, {}) }
        }
        compose.onNodeWithTag("diagnostics-switch").performScrollTo().assertIsEnabled().assertIsOn().performClick()
        compose.runOnIdle { assertEquals(listOf(false), choices) }
    }

    @Test fun withoutADsnNeitherTheSwitchNorAStoredChoiceStartsCollection() {
        // CI and local test builds leave ALBA_SENTRY_DSN empty.
        assumeTrue("This build has a Sentry DSN", BuildConfig.SENTRY_DSN.isBlank())
        val diagnostics = SentryAlbaDiagnostics(app)
        assertFalse(diagnostics.available)
        diagnostics.setEnabled(true)
        assertFalse(diagnostics.enabled.value)
        assertFalse(Sentry.isEnabled())
        // A preference left by a previously configured build cannot enable collection.
        app.getSharedPreferences("mela_diagnostics", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).commit()
        val restored = SentryAlbaDiagnostics(app).apply { initialize() }
        assertFalse(restored.enabled.value)
        assertFalse(Sentry.isEnabled())
    }
}
