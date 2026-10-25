package me.lvca.alba.app.localization

import android.app.LocaleManager
import android.os.LocaleList
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.Screenshots
import me.lvca.alba.app.testing.UI_WAIT
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercise system locale changes with native accessibility, independently of Compose's test clock. */
@SdkSuppress(minSdkVersion = 33)
class LocalizationTest {
    @get:Rule val onboarding = OnboardingRule(completed = false)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun language(tags: String) {
        instrumentation.runOnMainSync {
            instrumentation.targetContext.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tags)
        }
    }
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun label(text: String): AccessibilityNodeInfo? {
        // The native cache can retain pre-recreation Compose labels after locale changes.
        instrumentation.uiAutomation.clearCache()
        return find(instrumentation.uiAutomation.rootInActiveWindow) {
            it.text?.toString() == text || it.contentDescription?.toString() == text
        }
    }
    private fun waitFor(text: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + UI_WAIT
        do {
            label(text)?.let { return it }
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < end)
        Screenshots.capture("failure-LocalizationTest")
        val labels = mutableListOf<String>()
        find(instrumentation.uiAutomation.rootInActiveWindow) {
            it.text?.let { value -> labels.add(value.toString()) }
            it.contentDescription?.let { value -> labels.add(value.toString()) }
            false
        }
        error("Visible text not found: $text. Visible labels: $labels")
    }
    private fun click(text: String) {
        // Settle before the click only: every following step waits for its own text or settles before its own click.
        waitFor(text)
        instrumentation.uiAutomation.waitForIdle(500, UI_WAIT)
        var node = waitFor(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Could not click $text" }
    }
    @Test fun switchingLanguagePreservesOnboardingAndLocalizesGalleryAndSignIn() {
        val previous = instrumentation.targetContext.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        try {
            language("it")
            ActivityScenario.launch(MainActivity::class.java).use {
                waitFor("Le tue foto di iCloud.\nSu Android.")
                click("Continua")
                waitFor("Decidi tu.")
                language("en")
                waitFor("You’re in control.")
                language("it")
                waitFor("Decidi tu.")
                click("Prova la demo")
                waitFor("Alba")
                waitFor("Foto")
                click("Filtra e ordina")
                waitFor("Dimensione foto")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                click("Raccolte")
                waitFor("Preferiti")
                waitFor("Album condivisi")
                click("Album")
                waitFor("Nuovo album")
                click("Indietro")
                click("Apri account iCloud")
                waitFor("Accedi a iCloud")
                language("en")
                waitFor("Sign in to iCloud")
                assertNull(label("Your iCloud photos.\nOn Android."))
            }
        } finally { language(previous) }
    }
}
