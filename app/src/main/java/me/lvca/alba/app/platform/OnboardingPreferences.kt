package me.lvca.alba.app.platform

import android.content.Context
import androidx.core.content.edit
import me.lvca.alba.protocol.account.DemoModeStore

/** Installation-level introduction; independent of Apple accounts and backup consent. */
internal class OnboardingPreferences(context: Context) : DemoModeStore {
    override var enabled: Boolean
        get() = preferences.getBoolean(DEMO_SELECTED, false)
        set(value) { preferences.edit { putBoolean(DEMO_SELECTED, value) } }

    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    val completed: Boolean get() = preferences.getBoolean(COMPLETED, false)

    fun complete() {
        preferences.edit { putBoolean(COMPLETED, true) }
    }

    /** Stored on devices; device tests set them directly to start past or at the introduction. */
    companion object {
        // Named before the Alba rename; keep it.
        const val FILE = "mela-onboarding"
        const val COMPLETED = "completed"
        const val DEMO_SELECTED = "demo-explicitly-selected"
    }
}
