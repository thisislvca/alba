package me.lvca.alba.app.testing

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.platform.OnboardingPreferences

/** Existing gallery tests start past the introduction; welcome tests exercise the fresh-install path. */
class OnboardingRule(private val completed: Boolean = true) : ExternalResource() {
    private val preferences get() = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences(OnboardingPreferences.FILE, Context.MODE_PRIVATE)
    private var previous: Boolean? = null
    private var previousDemo = false

    override fun before() {
        previous = if (preferences.contains(OnboardingPreferences.COMPLETED)) preferences.getBoolean(OnboardingPreferences.COMPLETED, false) else null
        previousDemo = preferences.getBoolean(OnboardingPreferences.DEMO_SELECTED, false)
        check(preferences.edit().putBoolean(OnboardingPreferences.COMPLETED, completed).putBoolean(OnboardingPreferences.DEMO_SELECTED, completed).commit())
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AlbaApplication
        kotlinx.coroutines.runBlocking { app.graph.accountManager.selectDemo(completed) }
    }

    override fun after() {
        val edit = preferences.edit().putBoolean(OnboardingPreferences.DEMO_SELECTED, previousDemo)
        previous?.let { edit.putBoolean(OnboardingPreferences.COMPLETED, it) } ?: edit.remove(OnboardingPreferences.COMPLETED)
        check(edit.commit())
    }
}
