package dev.mela.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Existing gallery tests start past the introduction; welcome tests exercise the fresh-install path. */
class OnboardingRule(private val completed: Boolean = true) : ExternalResource() {
    private val preferences get() = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("mela-onboarding", Context.MODE_PRIVATE)
    private var previous: Boolean? = null
    private var previousDemo = false

    override fun before() {
        previous = if (preferences.contains("completed")) preferences.getBoolean("completed", false) else null
        previousDemo = preferences.getBoolean("demo-explicitly-selected", false)
        check(preferences.edit().putBoolean("completed", completed).putBoolean("demo-explicitly-selected", completed).commit())
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MelaApplication
        kotlinx.coroutines.runBlocking { app.graph.accountManager.selectDemo(completed) }
    }

    override fun after() {
        val edit = preferences.edit().putBoolean("demo-explicitly-selected", previousDemo)
        previous?.let { edit.putBoolean("completed", it) } ?: edit.remove("completed")
        check(edit.commit())
    }
}
