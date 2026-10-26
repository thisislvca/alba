package me.lvca.alba.app.testing

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.work.WorkManager
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.MainActivity
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * The whole app on Robolectric: [MainActivity] with the real graph (Room, repositories, view model) in demo mode.
 * Robolectric gives every test a fresh application, so its database, files and preferences start empty and nothing
 * one test changes reaches another.
 *
 * [onboarded] starts past the introduction with the demo selected, and its catalog in the repository and on screen
 * before the test's first Compose call ([DemoCatalogRule]). `false` starts at the introduction as a fresh install does.
 */
class AlbaAppRule(private val onboarded: Boolean = true) : TestRule {
    private val onboarding = OnboardingRule(onboarded)

    val compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity> = createAndroidComposeRule<MainActivity>()

    val activity: MainActivity get() = compose.activity

    val application: AlbaApplication get() = ApplicationProvider.getApplicationContext()

    fun string(id: Int, vararg args: Any): String = application.getString(id, *args)

    fun recreate() {
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
    }

    override fun apply(base: Statement, description: Description): Statement {
        val chain = RuleChain.outerRule(onboarding)
        val test = (if (onboarded) chain.around(DemoCatalogRule(compose)) else chain).around(compose).apply(base, description)
        return object : Statement() {
            override fun evaluate() {
                try {
                    test.evaluate()
                } finally {
                    stopBackgroundWork()
                }
            }
        }
    }

    /**
     * The activity is gone by now and its view model scopes are cancelled, but WorkManager's executors and Room's query
     * threads belong to the application and outlive the test. Left running, they keep reading and writing files and
     * bitmaps while the next test (and the JVM's shutdown) tears the Robolectric environment down. Best effort: a
     * failure here must not hide the test's own.
     */
    private fun stopBackgroundWork() {
        runCatching { WorkManager.getInstance(application).cancelAllWork() }
        runCatching { application.graph.database.close() }
    }
}
