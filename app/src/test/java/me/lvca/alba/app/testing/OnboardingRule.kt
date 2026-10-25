package me.lvca.alba.app.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.platform.OnboardingPreferences
import org.junit.rules.ExternalResource
import org.robolectric.shadows.ShadowStatFs

/**
 * Prepares Robolectric's fresh application for Alba: a phone with free space, a test WorkManager, and the
 * introduction either completed with the demo selected ([completed]) or not yet seen, as on a fresh install.
 *
 * Robolectric creates a new application, database, files and preferences for every test, so nothing needs restoring.
 */
class OnboardingRule(private val completed: Boolean = true) : ExternalResource() {
    override fun before() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric reports every volume as full; give the app a roomy phone so caches and previews write.
        ShadowStatFs.registerStats("/", FREE_BLOCKS, FREE_BLOCKS, FREE_BLOCKS)
        // WorkManager is a process-wide singleton; give each test's application its own synchronous instance.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        check(
            context.getSharedPreferences(OnboardingPreferences.FILE, Context.MODE_PRIVATE).edit()
                .putBoolean(OnboardingPreferences.COMPLETED, completed)
                .putBoolean(OnboardingPreferences.DEMO_SELECTED, completed)
                .commit(),
        )
        if (completed) runBlocking { (context as AlbaApplication).graph.accountManager.selectDemo(true) }
    }

    private companion object {
        /** 64 GB in 4 KB blocks. */
        const val FREE_BLOCKS = 16 * 1024 * 1024
    }
}
