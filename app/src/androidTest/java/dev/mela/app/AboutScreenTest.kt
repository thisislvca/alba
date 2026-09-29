package dev.mela.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class AboutScreenTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun label(id: Int) = compose.activity.getString(id)

    @Test fun aboutShowsFeaturesAndUpcomingAndRestoresWithoutLeavingAccount() {
        compose.waitUntil(15000) { compose.onAllNodesWithTag("account-button").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("account-button").performClick()
        compose.onNodeWithText(label(R.string.about_title)).performScrollTo().performClick()
        compose.onNodeWithTag("about-screen").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.about_intro)).assertIsDisplayed()
        compose.onNodeWithTag("about-source").assertIsDisplayed().assertHasClickAction()
        val info = compose.activity.packageManager.getPackageInfo(compose.activity.packageName, 0)
        compose.onNodeWithText(compose.activity.getString(R.string.about_version, "${info.versionName} (${info.longVersionCode})")).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.about_features)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.about_feature_shared_title)).performScrollTo().assertIsDisplayed()
        capture("about-features")
        compose.onNodeWithText(label(R.string.about_upcoming)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.about_upcoming_older_detail)).performScrollTo().assertIsDisplayed()
        capture("about-upcoming")
        compose.onNodeWithText(label(R.string.about_missing_support)).assertDoesNotExist()
        compose.onNodeWithTag("about-limitations").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("about-screen").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.about_missing_support)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("about-notices").performScrollTo().performClick()
        compose.onNodeWithText("The MIT License", substring = true).performScrollTo().assertExists()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("about-screen").assertDoesNotExist()
        compose.onNodeWithTag("profile-about").performScrollTo().assertIsDisplayed()
    }

    @Test fun diagnosticsRespectBuildConfigurationAndTheChoicePersists() {
        compose.waitUntil(15000) { compose.onAllNodesWithTag("account-button").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("account-button").performClick()
        compose.onNodeWithText(label(R.string.about_title)).performScrollTo().performClick()
        if (BuildConfig.SENTRY_DSN.isBlank()) {
            compose.onNodeWithTag("diagnostics-switch").performScrollTo().assertIsDisplayed().assertIsOff().assertIsNotEnabled()
            compose.onNodeWithText(label(R.string.about_diagnostics_unavailable)).assertIsDisplayed()
            val app = compose.activity.application as MelaApplication
            org.junit.Assert.assertFalse(app.diagnostics.enabled.value)
            app.diagnostics.setEnabled(true)
            org.junit.Assert.assertFalse(app.diagnostics.enabled.value)
            org.junit.Assert.assertFalse(io.sentry.Sentry.isEnabled())
            // A preference left by a previously configured build cannot enable collection.
            compose.activity.getSharedPreferences("mela_diagnostics", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", true).commit()
            val restored = SentryMelaDiagnostics(compose.activity)
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

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        val dir = compose.activity.getExternalFilesDir("release-prep")!!.apply { mkdirs() }
        java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
