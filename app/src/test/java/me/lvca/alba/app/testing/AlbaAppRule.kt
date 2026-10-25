package me.lvca.alba.app.testing

import android.content.Context
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.platform.OnboardingPreferences
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.shadows.ShadowStatFs

/**
 * The whole app on Robolectric: [MainActivity] with the real graph (Room, repositories, view model) in demo mode.
 * Robolectric gives every test a fresh application, so its database, files and preferences start empty and nothing
 * one test changes reaches another.
 *
 * [onboarded] starts past the introduction with the demo selected; `false` starts at the introduction as a fresh
 * install does.
 */
class AlbaAppRule(private val onboarded: Boolean = true) : TestRule {
    val compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity> = createAndroidComposeRule<MainActivity>()

    val activity: MainActivity get() = compose.activity

    val application: AlbaApplication get() = ApplicationProvider.getApplicationContext()

    fun string(id: Int, vararg args: Any): String = application.getString(id, *args)

    fun recreate() {
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
    }

    override fun apply(base: Statement, description: Description): Statement =
        RuleChain.outerRule(Setup()).around(compose).apply(base, description)

    private inner class Setup : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<Context>()
                loadNativeGraphics()
                // Robolectric reports every volume as full; give the app a roomy phone so caches and previews write.
                ShadowStatFs.registerStats("/", FREE_BLOCKS, FREE_BLOCKS, FREE_BLOCKS)
                // WorkManager is a process-wide singleton; give each test's application its own synchronous instance.
                WorkManagerTestInitHelper.initializeTestWorkManager(context)
                context.getSharedPreferences(OnboardingPreferences.FILE, Context.MODE_PRIVATE).edit()
                    .putBoolean(OnboardingPreferences.COMPLETED, onboarded)
                    .putBoolean(OnboardingPreferences.DEMO_SELECTED, onboarded)
                    .commit()
                if (onboarded) runBlocking { (context as AlbaApplication).graph.accountManager.selectDemo(true) }
                base.evaluate()
            }
        }
    }

    private companion object {
        /** 64 GB in 4 KB blocks. */
        const val FREE_BLOCKS = 16 * 1024 * 1024
    }
}
