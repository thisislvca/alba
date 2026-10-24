package me.lvca.alba.app.localization

import android.app.LocaleManager
import android.content.res.Configuration
import android.os.LocaleList
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.R
import me.lvca.alba.app.messages.UiMessage
import me.lvca.alba.app.messages.localizedStoredMessage
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
    @Test fun pluralsMessagesAndUnsupportedLanguagesUseNativeResources() {
        val context = instrumentation.targetContext
        fun localized(tag: String) = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) })
        val italian = localized("it")
        assertEquals("1 elemento", italian.resources.getQuantityString(R.plurals.item_count, 1, 1))
        assertEquals("2 elementi", italian.resources.getQuantityString(R.plurals.item_count, 2, 2))
        val pending = UiMessage(R.string.album_created, listOf("Viaggi"))
        assertEquals("Creato: Viaggi", pending.resolve(italian))
        assertEquals("Created Viaggi", pending.resolve(localized("en")))
        assertEquals("Created Weekend with 1 photo.", UiMessage.Quantity(R.plurals.album_created_with_photos, 1, listOf("Weekend", 1)).resolve(localized("en")))
        assertEquals("Created Weekend with 2 photos.", UiMessage.Quantity(R.plurals.album_created_with_photos, 2, listOf("Weekend", 2)).resolve(localized("en")))
        assertEquals("Album Viaggi creato con 2 foto.", UiMessage.Quantity(R.plurals.album_created_with_photos, 2, listOf("Viaggi", 2)).resolve(italian))
        assertEquals("Library", localized("fr").getString(R.string.library))
        assertEquals("Per ora Alba carica solo originali JPEG.", localizedStoredMessage("Mela currently uploads JPEG originals only.").resolve(italian))
        assertTrue(localizedStoredMessage("Upload result was lost. Mela will check iCloud without uploading again.").resolve(italian).contains("senza ripetere"))
        for (message in listOf(me.lvca.alba.engine.source.UnsupportedUploadException.MESSAGE,
            me.lvca.alba.engine.source.UnsupportedUploadException.ANIMATED, me.lvca.alba.engine.source.UnsupportedUploadException.VIDEO_CODEC,
            me.lvca.alba.engine.source.UnsupportedUploadException.INVALID,
            "iCloud is processing this file. The original is still on this phone.",
            "iCloud could not process this file. The original is still on this phone. This upload will not be repeated.")) {
            assertNotEquals(italian.getString(R.string.operation_needs_attention), localizedStoredMessage(message).resolve(italian))
            assertEquals(me.lvca.alba.engine.model.StoredMessage.decode(message)?.legacyText ?: message,
                localizedStoredMessage(message).resolve(localized("en")))
        }
        assertTrue(italian.getString(R.string.backup_disclosure, "family@example.com").contains("family@example.com"))
    }
}
