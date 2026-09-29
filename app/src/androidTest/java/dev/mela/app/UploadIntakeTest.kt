package dev.mela.app

import android.content.ContentValues
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.LocaleList
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.mela.protocol.account.ICloudAccountState
import java.io.File
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
            for ((name,mime) in listOf("png.png" to "image/png", "avc.mp4" to "video/mp4", "animated.gif" to "image/gif")) {
                val uri = requireNotNull(resolver.insert(if (mime.startsWith("video/")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "mela-intake-${System.nanoTime()}-$name")
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, if (mime.startsWith("video/")) "Movies/MelaTests" else "Pictures/MelaTests")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }))
                uris += uri
                InstrumentationRegistry.getInstrumentation().context.assets.open("upload-formats/$name").use { input ->
                    resolver.openOutputStream(uri)!!.use { input.copyTo(it) }
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            val configuration = Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(language)) }
            val localized = context.createConfigurationContext(configuration)
            var queued: List<Uri>? = null
            compose.setContent {
                IncomingMediaScreen(IncomingMediaRequest(uris.map(Uri::toString)),
                    GalleryUiState(accountState = ICloudAccountState.SignedIn("test@example.invalid")),
                    close = {}, connect = { error("Must use supplied screen state only") }, upload = { queued = it })
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("queue-incoming-upload").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(localized.getString(R.string.incoming_upload_explanation)).assertIsDisplayed()
            assertNull(queued)
            compose.onNodeWithTag("queue-incoming-upload").performClick()
            compose.onNodeWithText(localized.resources.getQuantityString(R.plurals.incoming_upload_confirm, 2, 2)).assertIsDisplayed()
            compose.onNodeWithText(localized.getString(R.string.cancel)).performClick()
            assertNull(queued)
            compose.onNodeWithTag("queue-incoming-upload").performClick()
            compose.onNodeWithTag("confirm-incoming-upload").assertIsDisplayed()
            compose.waitForIdle()
            android.os.SystemClock.sleep(350)
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
            val directory = context.getExternalFilesDir("upload-formats")!!.apply { mkdirs() }
            File(directory,"intake-$language.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            compose.onNodeWithTag("confirm-incoming-upload").performClick()
            compose.runOnIdle { assertEquals(uris.take(2), queued) }
            compose.onNodeWithTag("queue-incoming-upload").assertIsNotEnabled()
        } finally { uris.forEach { resolver.delete(it,null,null) } }
    }
}
