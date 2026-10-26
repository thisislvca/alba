package me.lvca.alba.app.testing

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.platform.OnboardingPreferences
import org.junit.rules.ExternalResource

/**
 * Starts past the introduction with the demo selected ([completed]), or at the introduction as a fresh install does.
 *
 * Nothing is restored afterwards: the test orchestrator clears the app's data before every test (`clearPackageData`
 * in the build's device-test conventions), so no test sees another's preferences.
 */
class OnboardingRule(private val completed: Boolean = true) : ExternalResource() {
    override fun before() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AlbaApplication
        check(
            app.getSharedPreferences(OnboardingPreferences.FILE, Context.MODE_PRIVATE).edit()
                .putBoolean(OnboardingPreferences.COMPLETED, completed)
                .putBoolean(OnboardingPreferences.DEMO_SELECTED, completed)
                .commit(),
        )
        runBlocking { app.graph.accountManager.selectDemo(completed) }
    }
}
