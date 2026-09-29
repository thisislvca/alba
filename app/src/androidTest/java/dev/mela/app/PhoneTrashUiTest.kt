package dev.mela.app

import android.Manifest
import android.content.ContentValues
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.mela.engine.source.AndroidMediaStoreSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@SdkSuppress(minSdkVersion = 33)
class PhoneTrashUiTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun systemButton(id: String): AccessibilityNodeInfo? =
        instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/$id")?.firstOrNull()
    private fun confirm(id: String) {
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        var button: AccessibilityNodeInfo? = null
        while (button == null && android.os.SystemClock.uptimeMillis() < deadline) {
            button = systemButton(id)
            if (button == null) android.os.SystemClock.sleep(100)
        }
        checkNotNull(button) { "Android confirmation did not appear: $id" }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        instrumentation.waitForIdleSync()
    }
    @Test fun localVideoPlaysAndAndroidTrashCanBeCancelledConfirmedAndRestored() {
        val context = compose.activity.applicationContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_VIDEO)
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "mela-native-trash-test.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/MelaTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        try {
            instrumentation.context.assets.open("portrait_video.mp4").use { input -> resolver.openOutputStream(uri)!!.use(input::copyTo) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val source = AndroidMediaStoreSource(context)
            val id = runBlocking { source.scanImages().single { it.contentUri == uri.toString() }.id }
            compose.activityRule.scenario.recreate()
            val model = androidx.lifecycle.ViewModelProvider(compose.activity)[MelaViewModel::class.java]
            compose.waitUntil(15_000) { model.uiState.value.items.any { it.id == id } }
            compose.onNodeWithTag("gallery-grid").performScrollToKey(id)
            compose.onNodeWithTag("media-$id").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.ready))).fetchSemanticsNodes().isNotEmpty() }
            val playerBounds = compose.onNodeWithTag("video-player").fetchSemanticsNode().boundsInRoot
            assertTrue("Portrait video must use the available height", playerBounds.height > playerBounds.width)
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val directory = context.getExternalFilesDir("table-stakes")!!.apply { mkdirs() }
            java.io.File(directory, "portrait-video.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            onView(withContentDescription("Play")).perform(click())
            compose.waitUntil(5_000) { compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.playing))).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(context.getString(R.string.photo_details_and_actions)).performClick()
            compose.onNodeWithText(context.getString(R.string.trash_phone_copy)).performScrollTo().performClick()
            confirm("button2")
            compose.onNodeWithTag("photo-details-panel").assertIsDisplayed()
            assertFalse(runBlocking { source.scanImages().single { it.id == id }.isTrashed })
            compose.onNodeWithText(context.getString(R.string.trash_phone_copy)).performScrollTo().performClick()
            confirm("button1")
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isEmpty() }
            assertTrue(runBlocking { source.scanImages().single { it.id == id }.isTrashed })
            compose.onNodeWithTag("tab-COLLECTIONS").performClick()
            compose.onNodeWithTag("collections-grid").performScrollToNode(hasText(context.getString(R.string.phone_trash)))
            compose.onNodeWithText(context.getString(R.string.phone_trash)).performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("media-$id").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("media-$id").performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.photo_details_and_actions)).performClick()
            compose.onNodeWithText(context.getString(R.string.restore_photo)).performScrollTo().performClick()
            confirm("button1")
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isEmpty() }
            assertFalse(runBlocking { source.scanImages().single { it.id == id }.isTrashed })
        } finally { resolver.delete(uri, null, null) }
    }
}
