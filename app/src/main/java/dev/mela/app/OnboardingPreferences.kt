package dev.mela.app

import android.content.Context
import androidx.core.content.edit

/** Installation-level introduction; independent of Apple accounts and backup consent. */
internal class OnboardingPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("mela-onboarding", Context.MODE_PRIVATE)

    val completed: Boolean get() = preferences.getBoolean("completed", false)

    fun complete() {
        preferences.edit { putBoolean("completed", true) }
    }
}
