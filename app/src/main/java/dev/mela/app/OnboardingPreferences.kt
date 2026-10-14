package dev.mela.app

import android.content.Context
import androidx.core.content.edit

/** Installation-level introduction; independent of Apple accounts and backup consent. */
internal class OnboardingPreferences(context: Context) : dev.mela.protocol.account.DemoModeStore {
    override var enabled: Boolean
        get() = preferences.getBoolean("demo-explicitly-selected", false)
        set(value) { preferences.edit { putBoolean("demo-explicitly-selected", value) } }

    private val preferences = context.applicationContext.getSharedPreferences("mela-onboarding", Context.MODE_PRIVATE)

    val completed: Boolean get() = preferences.getBoolean("completed", false)

    fun complete() {
        preferences.edit { putBoolean("completed", true) }
    }
}
