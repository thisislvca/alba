package dev.mela.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Existing gallery tests start past the introduction; welcome tests exercise the fresh-install path. */
class OnboardingRule(private val completed: Boolean = true) : ExternalResource() {
    private val preferences get() = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("mela-onboarding", Context.MODE_PRIVATE)
    private var previous: Boolean? = null

    override fun before() {
        previous = if (preferences.contains("completed")) preferences.getBoolean("completed", false) else null
        check(preferences.edit().putBoolean("completed", completed).commit())
    }

    override fun after() {
        val edit = preferences.edit()
        previous?.let { edit.putBoolean("completed", it) } ?: edit.remove("completed")
        check(edit.commit())
    }
}
