package dev.mela.app

import android.app.LocaleManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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
        val end = SystemClock.uptimeMillis() + 10_000
        do {
            label(text)?.let { return it }
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < end)
        capture("failure")
        val labels = mutableListOf<String>()
        find(instrumentation.uiAutomation.rootInActiveWindow) {
            it.text?.let { value -> labels.add(value.toString()) }
            it.contentDescription?.let { value -> labels.add(value.toString()) }
            false
        }
        error("Visible text not found: $text. Visible labels: $labels")
    }
    private fun click(text: String) {
        waitFor(text)
        instrumentation.uiAutomation.waitForIdle(500, 10_000)
        var node = waitFor(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Could not click $text" }
        instrumentation.uiAutomation.waitForIdle(500, 10_000)
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350) // Capture settled native window transitions, not a rotation/recreation frame.
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val dir = instrumentation.targetContext.getExternalFilesDir("localization")!!.apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun switchingLanguagePreservesOnboardingAndLocalizesGalleryAndSignIn() {
        val previous = instrumentation.targetContext.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        try {
            language("it")
            ActivityScenario.launch(MainActivity::class.java).use {
                waitFor("Le tue foto di iCloud.\nSu Android.")
                capture("01-onboarding-it")
                click("Continua")
                waitFor("Decidi tu.")
                language("en")
                waitFor("You’re in control.")
                language("it")
                waitFor("Decidi tu.")
                capture("02-choices-it")
                click("Prova la demo")
                waitFor("Alba")
                waitFor("Demo")
                capture("03-library-it")
                click("Filtra e ordina")
                waitFor("Dimensione foto")
                capture("04-filters-it")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                click("Raccolte")
                waitFor("Preferiti")
                waitFor("Album condivisi")
                click("Album")
                waitFor("Nuovo album")
                capture("05-collections-it")
                click("Torna alle raccolte")
                click("Apri account iCloud")
                waitFor("Accedi a iCloud")
                capture("06-sign-in-it")
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
        for (message in listOf(dev.mela.engine.source.UnsupportedUploadException.MESSAGE,
            dev.mela.engine.source.UnsupportedUploadException.ANIMATED, dev.mela.engine.source.UnsupportedUploadException.VIDEO_CODEC,
            dev.mela.engine.source.UnsupportedUploadException.INVALID,
            "iCloud is processing this file. The original is still on this phone.",
            "iCloud could not process this file. The original is still on this phone. This upload will not be repeated.")) {
            assertNotEquals(italian.getString(R.string.operation_needs_attention), localizedStoredMessage(message).resolve(italian))
            assertEquals(message, localizedStoredMessage(message).resolve(localized("en")))
        }
        assertTrue(italian.getString(R.string.backup_disclosure, "family@example.com").contains("family@example.com"))
    }
}
