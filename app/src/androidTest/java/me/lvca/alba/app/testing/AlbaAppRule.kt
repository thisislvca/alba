package me.lvca.alba.app.testing

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.di.AlbaGraph
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * The whole app on a device: [MainActivity] with the real graph in demo mode, like the JVM tests' rule of the same name.
 *
 * [onboarded] starts past the introduction with the demo selected, and its catalog in the repository and on screen
 * before the test's first Compose call ([DemoCatalogRule]). `false` starts at the introduction as a fresh install does.
 * A failing test saves a screenshot and its semantics tree ([FailureCapture]).
 */
class AlbaAppRule(private val onboarded: Boolean = true) : TestRule {
    val compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity> = createAndroidComposeRule<MainActivity>()

    val activity: MainActivity get() = compose.activity

    val graph: AlbaGraph get() = ApplicationProvider.getApplicationContext<AlbaApplication>().graph

    override fun apply(base: Statement, description: Description): Statement {
        val chain = RuleChain.outerRule(OnboardingRule(onboarded))
        return (if (onboarded) chain.around(DemoCatalogRule(compose)) else chain)
            .around(compose)
            .around(FailureCapture { compose })
            .apply(base, description)
    }
}
