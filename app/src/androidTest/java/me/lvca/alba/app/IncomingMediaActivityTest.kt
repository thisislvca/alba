package me.lvca.alba.app

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class IncomingMediaActivityTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(350)
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        val directory = context.getExternalFilesDir("table-stakes-finish")!!.apply { mkdirs() }
        java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun image(): Uri {
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "alba-incoming-test-${System.nanoTime()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg"); put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/AlbaTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }
    private fun intent(action: String) = Intent(action).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

    @Test fun openWithUsesRealIntentResolutionAndRestoresThePhotoAfterRecreation() {
        val uri = image()
        try {
            ActivityScenario.launch<MainActivity>(intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")).use { scenario ->
                compose.waitUntil(15_000) {
                    compose.onNodeWithText(context.getString(R.string.demo_upload_disclosure)).isDisplayed()
                }
                compose.onNodeWithText(context.getString(R.string.demo_upload_disclosure)).assertIsDisplayed()
                capture("open-with")
                scenario.recreate()
                compose.onNodeWithTag("incoming-media").assertIsDisplayed()
                assertNull((context as AlbaApplication).graph.accountManager.authorizedSession)
            }
        } finally { context.contentResolver.delete(uri, null, null) }
    }
    @Test fun multipleShareIsPreviewedAndDoesNotSilentlyUpload() {
        val one = image(); val two = image()
        try {
            ActivityScenario.launch<MainActivity>(intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/jpeg"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(one, two))
            }).use {
                compose.waitUntil(15_000) { compose.onAllNodesWithText(context.getString(R.string.incoming_position, 1, 2)).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(context.getString(R.string.demo_upload_title)).assertIsDisplayed()
                assertNull((context as AlbaApplication).graph.accountManager.authorizedSession)
                val image = compose.onNodeWithTag("incoming-image-0")
                image.performTouchInput { doubleClick() }
                image.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, context.getString(R.string.photo_zoomed)))
                image.performTouchInput { swipeLeft() }
                compose.onNodeWithText(context.getString(R.string.incoming_position, 1, 2)).assertIsDisplayed()
                image.performTouchInput { doubleClick() }
                image.performTouchInput { swipeLeft() }
                compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(R.string.incoming_position, 2, 2)).fetchSemanticsNodes().isNotEmpty() }
            }
        } finally { context.contentResolver.delete(one, null, null); context.contentResolver.delete(two, null, null) }
    }
    @Test fun sharedPhotoCanBeImportedIntoDemoAfterExplicitConfirmation() {
        val uri = image()
        try {
            ActivityScenario.launch<MainActivity>(intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, uri)
            }).use {
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("queue-incoming-upload").fetchSemanticsNodes().isNotEmpty() }
                val graph = (context as AlbaApplication).graph
                val before = kotlinx.coroutines.runBlocking { graph.demoCatalog.fetchPage(null, 100).records.map { it.id }.toSet() }
                compose.onNodeWithTag("queue-incoming-upload").performClick()
                compose.waitUntilDisplayed(hasTestTag("confirm-incoming-upload"))
                compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
                assertEquals(before, kotlinx.coroutines.runBlocking { graph.demoCatalog.fetchPage(null, 100).records.map { it.id }.toSet() })
                compose.onNodeWithTag("queue-incoming-upload").performClick()
                compose.waitUntilDisplayed(hasTestTag("confirm-incoming-upload")).performClick()
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithText(context.getString(R.string.demo_import_complete)).fetchSemanticsNodes().isNotEmpty()
                }
                val added = kotlinx.coroutines.runBlocking { graph.demoCatalog.fetchPage(null, 100).records.single { it.id !in before } }
                assertEquals(80, added.width); assertEquals(60, added.height)
                assertNull(graph.accountManager.authorizedSession)
                capture("share-to-demo-imported")
            }
        } finally { context.contentResolver.delete(uri, null, null) }
    }

    @Test fun unsafeSchemesAreRejectedAndMissingFilesHaveHonestErrors() {
        assertTrue(incomingMedia(Intent(Intent.ACTION_VIEW, Uri.parse("file:///data/private.jpg")))!!.uris.isEmpty())
        assertTrue(incomingMedia(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/photo.jpg")))!!.uris.isEmpty())
        val uri = image(); context.contentResolver.delete(uri, null, null)
        ActivityScenario.launch<MainActivity>(intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").apply { removeFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }).use {
            compose.waitUntil(15_000) { compose.onAllNodesWithText(context.getString(R.string.incoming_unsupported)).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText(context.getString(R.string.incoming_file_missing)).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText(context.getString(R.string.incoming_access_lost)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(context.getString(R.string.share)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.incoming_connect)).assertDoesNotExist()
            capture("missing-shared-file")
        }
    }
}
