package dev.mela.app

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.mela.app.ui.ICloudAccountScreen
import dev.mela.app.ui.theme.MelaTheme
import dev.mela.engine.model.BackupView
import dev.mela.engine.model.CloudStorageUsage
import dev.mela.engine.model.StorageCategory
import dev.mela.protocol.account.ICloudAccountState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Repeatable, real UI captures for the store artwork. Run alone on a disposable demo device. */
class StoreScreenshotTest {
    // Check before Compose runs the test in its coroutine, so JUnit records a skip.
    @get:Rule(order = 0) val captureRequested = object : org.junit.rules.ExternalResource() {
        override fun before() {
            assumeTrue("Store captures run only when explicitly requested",
                InstrumentationRegistry.getArguments().getString("captureStoreScreenshots") == "true")
        }
    }
    @get:Rule(order = 1) val onboarding = OnboardingRule()
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()
    private val repository get() = (compose.activity.application as MelaApplication).graph.galleryRepository

    private fun capture(name: String) {
        compose.waitForIdle()
        SystemClock.sleep(800) // Settle screen transitions and async photo decoding before the capture.
        if (name == "02-viewer") {
            compose.waitUntil(20_000) {
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
                    .fetchSemanticsNodes().isEmpty()
            }
        }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val dir = compose.activity.getExternalFilesDir("store-screenshots")!!.apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun open(id: String) {
        compose.scrollToGalleryMedia(id)
        compose.onNodeWithTag("media-$id").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(if (id == "fixture:icloud:0019") "video-player" else "viewer-image-$id").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun captureStoreScreens() {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("media-fixture:icloud:0020").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithText("Not now").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Not now").performScrollTo().performClick()
        }
        // Warm the real demo previews so every captured collection mosaic has its photos.
        runBlocking {
            repository.observeGallery().first().forEach { repository.ensurePreview(it.id) }
        }
        compose.scrollToGalleryMedia("fixture:icloud:0001")
        capture("01-library")
        open("fixture:icloud:0008")
        capture("02-viewer")
        compose.onNodeWithContentDescription("Back to gallery").performClick()
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-shared").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Family moments").fetchSemanticsNodes().isNotEmpty() }
        capture("03-shared-albums")
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasText("Good company"))
        compose.onNodeWithText("Good company").performClick()
        val sharedPhoto = "shared:demo-library:private:owner:album-4:photo-6"
        compose.onNodeWithTag("gallery-grid").performScrollToKey(sharedPhoto)
        compose.onNodeWithTag("media-shared:demo-library:private:owner:album-4:photo-10").assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.cancel_selection)).assertDoesNotExist()
        capture("05-share")
        compose.onNodeWithContentDescription("Back to collections").performClick()
        compose.onNodeWithContentDescription("Back to collections").performClick()
        compose.onNodeWithTag("tab-LIBRARY").performClick()
        open("fixture:icloud:0019")
        compose.waitUntil(15_000) { compose.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Ready")).fetchSemanticsNodes().isNotEmpty() }
        capture("06-video")
        // Show the production backup settings with a synthetic account, as in
        // ProfileScreenTest. No credentials, Apple requests or backup jobs run.
        compose.activity.runOnUiThread {
            androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
            compose.activity.setContent {
                MelaTheme(darkTheme = false) {
                    ICloudAccountScreen(
                        state = ICloudAccountState.SignedIn("demo@example.com"),
                        diagnostics = GalleryUiState(
                            accountState = ICloudAccountState.SignedIn("demo@example.com"),
                            accountInfo = AccountInfo(storage = CloudStorageUsage(
                                72_000_000_000, 200_000_000_000,
                                listOf(StorageCategory("Photos", 64_000_000_000),
                                    StorageCategory("Documents", 4_000_000_000),
                                    StorageCategory("Backup", 2_000_000_000),
                                    StorageCategory("Other", 2_000_000_000)),
                                1_790_000_000_000,
                            )),
                        ),
                        onBack = {}, onSignIn = { _, _ -> }, onSubmitTwoFactor = {},
                        onResendTwoFactor = {}, onSignOut = {}, backup = BackupView(false),
                        onEnableAutomaticBackup = {}, onDisableAutomaticBackup = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("profile-backup").performScrollTo().performClick()
        compose.onNodeWithText("Set up automatic backup").assertIsDisplayed()
        compose.onNodeWithText("Cloud storage").assertIsDisplayed()
        capture("04-backup")
    }
}
