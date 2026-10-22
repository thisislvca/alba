package me.lvca.alba.app.gallery

import android.Manifest
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.AlbaViewModel
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.LONG_WAIT
import me.lvca.alba.app.testing.MediaStoreFixtures
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.SystemWindows
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.testing.waitForVideoPlaying
import me.lvca.alba.engine.source.AndroidMediaStoreSource
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@SdkSuppress(minSdkVersion = 33)
class PhoneTrashUiTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** Answers Android's own trash or restore confirmation, which opens in a separate platform window. */
    private fun confirm(id: String) {
        checkNotNull(SystemWindows.await(id, UI_WAIT)) { "Android confirmation did not appear: $id" }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        instrumentation.waitForIdleSync()
    }
    @Test fun localVideoPlaysAndAndroidTrashCanBeCancelledConfirmedAndRestored() {
        val context = compose.activity.applicationContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_VIDEO)
        val resolver = context.contentResolver
        val uri = MediaStoreFixtures.insertAsset(instrumentation.context.assets, "portrait_video.mp4", "alba-native-trash-test.mp4", "video/mp4", "Movies/AlbaTest")
        try {
            val source = AndroidMediaStoreSource(context)
            val id = runBlocking { source.scanImages().single { it.contentUri == uri.toString() }.id }
            compose.activityRule.scenario.recreate()
            val model = androidx.lifecycle.ViewModelProvider(compose.activity)[AlbaViewModel::class.java]
            // A new MediaStore item reaches the gallery through a device scan, which can queue behind other repository work.
            compose.waitUntil(LONG_WAIT) { model.gallery.state.value.items.any { it.id == id } }
            compose.scrollToGalleryMedia(id)
            compose.onNodeWithTag("media-$id").performClick()
            compose.waitForVideoPlaying()
            val playerBounds = compose.onNodeWithTag("video-player").fetchSemanticsNode().boundsInRoot
            assertTrue("Portrait video must use the available height", playerBounds.height > playerBounds.width)
            compose.waitUntil(SHORT_WAIT) { compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.playing))).fetchSemanticsNodes().isNotEmpty() }
            compose.openViewerDetails()
            compose.onNodeWithText(context.getString(R.string.trash_phone_copy)).performScrollTo().performClick()
            confirm("button2")
            compose.onNodeWithTag("photo-details-panel").assertIsDisplayed()
            assertFalse(runBlocking { source.scanImages().single { it.id == id }.isTrashed })
            compose.onNodeWithText(context.getString(R.string.trash_phone_copy)).performScrollTo().performClick()
            confirm("button1")
            compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isEmpty() }
            assertTrue(runBlocking { source.scanImages().single { it.id == id }.isTrashed })
            compose.onNodeWithTag("tab-COLLECTIONS").performClick()
            compose.onNodeWithTag("collections-grid").performScrollToNode(hasText(context.getString(R.string.phone_trash)))
            compose.onNodeWithText(context.getString(R.string.phone_trash)).performClick()
            compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("media-$id").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("media-$id").performClick()
            compose.openViewerDetails()
            compose.onNodeWithText(context.getString(R.string.restore_photo)).performScrollTo().performClick()
            confirm("button1")
            compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isEmpty() }
            assertFalse(runBlocking { source.scanImages().single { it.id == id }.isTrashed })
        } finally { resolver.delete(uri, null, null) }
    }
}
