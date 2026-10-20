package me.lvca.alba.app.intake

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.LocaleList
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.LONG_WAIT
import me.lvca.alba.app.testing.MediaStoreFixtures
import me.lvca.alba.app.ui.intake.IncomingMediaScreen
import me.lvca.alba.protocol.account.ICloudAccountState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

@SdkSuppress(minSdkVersion = 33)
class UploadIntakeTest {
    private val compose = createComposeRule()

    // Dialogs obtain configuration from their Android window. Set the real app locale
    // before the test activity launches, then restore it after that activity closes.
    @get:Rule val rules: TestRule = RuleChain.outerRule(object : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val manager = instrumentation.targetContext.getSystemService(LocaleManager::class.java)
                val previous = manager.applicationLocales
                try {
                    instrumentation.runOnMainSync {
                        manager.applicationLocales = LocaleList.forLanguageTags(if (description.methodName.startsWith("italian")) "it" else "en")
                    }
                    base.evaluate()
                } finally { instrumentation.runOnMainSync { manager.applicationLocales = previous } }
            }
        }
    }).around(compose)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun englishIntakeConfirmsSupportedPhotosAndVideosWithoutSendingUnsupportedFiles() = checkIntake("en")
    @Test fun italianIntakeConfirmsSupportedPhotosAndVideosWithoutSendingUnsupportedFiles() = checkIntake("it")

    private fun checkIntake(language: String) {
        val resolver = context.contentResolver
        val uris = mutableListOf<Uri>()
        try {
            for ((name, mime) in listOf("png.png" to "image/png", "avc.mp4" to "video/mp4", "animated.gif" to "image/gif")) {
                uris += MediaStoreFixtures.insertAsset(InstrumentationRegistry.getInstrumentation().context.assets, "upload-formats/$name",
                    "alba-intake-${System.nanoTime()}-$name", mime, if (mime.startsWith("video/")) "Movies/AlbaTests" else "Pictures/AlbaTests")
            }
            val configuration = Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(language)) }
            val localized = context.createConfigurationContext(configuration)
            var queued: List<Uri>? = null
            compose.setContent {
                IncomingMediaScreen(IncomingRequest.Media(uris.map(Uri::toString)),
                    ICloudAccountState.SignedIn("test@example.invalid"), message = null,
                    close = {}, connect = { error("Must use supplied screen state only") }, upload = { queued = it })
            }
            compose.waitUntil(LONG_WAIT) { compose.onAllNodesWithTag("queue-incoming-upload").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(localized.getString(R.string.incoming_upload_explanation)).assertIsDisplayed()
            assertNull(queued)
            compose.onNodeWithTag("queue-incoming-upload").performClick()
            compose.onNodeWithText(localized.resources.getQuantityString(R.plurals.incoming_upload_confirm, 2, 2)).assertIsDisplayed()
            compose.onNodeWithText(localized.getString(R.string.cancel)).performClick()
            assertNull(queued)
            compose.onNodeWithTag("queue-incoming-upload").performClick()
            compose.onNodeWithTag("confirm-incoming-upload").assertIsDisplayed()
            compose.onNodeWithTag("confirm-incoming-upload").performClick()
            compose.runOnIdle { assertEquals(uris.take(2), queued) }
            compose.onNodeWithTag("queue-incoming-upload").assertIsNotEnabled()
        } finally { uris.forEach { resolver.delete(it, null, null) } }
    }
}
