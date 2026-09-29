package dev.mela.app

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
    @get:Rule val compose = createEmptyComposeRule()
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
            put(MediaStore.MediaColumns.DISPLAY_NAME, "mela-incoming-test-${System.nanoTime()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg"); put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/MelaTest")
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
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("incoming-media").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(context.getString(R.string.incoming_upload_explanation)).assertIsDisplayed()
                capture("open-with")
                scenario.recreate()
                compose.onNodeWithTag("incoming-media").assertIsDisplayed()
                assertNull((context as MelaApplication).graph.accountManager.authorizedSession)
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
                compose.onNodeWithText(context.getString(R.string.incoming_connect)).assertIsDisplayed()
                assertNull((context as MelaApplication).graph.accountManager.authorizedSession)
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
