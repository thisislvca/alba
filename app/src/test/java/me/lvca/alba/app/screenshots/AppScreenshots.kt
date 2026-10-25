package me.lvca.alba.app.screenshots

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureScreenRoboImage
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.R
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.gallery.GalleryState
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.setAlbaContent
import me.lvca.alba.app.transfers.TransferState
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * Screenshot goldens of the app's main states, rendered from fixed state through `AlbaApp` (no repository, clock or
 * network). Each subclass is one variant: a window size, a theme or a font scale. The goldens live in
 * `src/test/screenshots/<variant>/`; CI verifies them on Linux and re-records them on request (README "Testing").
 *
 * Without a Roborazzi mode (`-Proborazzi.test.verify=true`, `...record=true`) the captures are skipped, so an ordinary
 * `testDebugUnitTest` run only checks that each state renders.
 */
@RunWith(AndroidJUnit4::class)
abstract class AppScreenshots(
    private val variant: String,
    private val darkTheme: Boolean = false,
    private val fontScale: Float = 1f,
    /** The states this variant captures; every state is captured in [StandardScreenshots]. */
    private val states: Set<String>? = null,
) {
    @get:Rule val compose = createComposeRule()

    @Before fun fixEnvironment() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        if (fontScale != 1f) RuntimeEnvironment.setFontScale(fontScale)
    }

    private val context get() = ApplicationProvider.getApplicationContext<AlbaApplication>()

    private fun state(name: String) = assumeTrue("$variant does not capture $name", states == null || name in states)

    private fun show(gallery: GalleryState = ScreenshotFixtures.gallery(), session: SessionState = ScreenshotFixtures.signedIn,
        transfers: TransferState = TransferState()) =
        compose.setAlbaContent(gallery, session, transfers, darkTheme = darkTheme)

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/$variant/$name.png")
    }

    @Test fun gallery() {
        state("gallery")
        show()
        capture("gallery")
    }

    @Test fun gallerySelection() {
        state("gallery-selection")
        val gallery = ScreenshotFixtures.gallery()
        show(gallery.copy(selection = gallery.items.take(3).map { it.id }.toSet()))
        capture("gallery-selection")
    }

    @Test fun galleryOffline() {
        state("gallery-offline")
        show(session = ScreenshotFixtures.offline)
        capture("gallery-offline")
    }

    @Test fun emptySignedOut() {
        state("empty-signed-out")
        show(GalleryState(), ScreenshotFixtures.signedOut)
        capture("empty-signed-out")
    }

    @Test fun collections() {
        state("collections")
        show()
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        capture("collections")
    }

    @Test fun search() {
        state("search")
        show()
        compose.onNodeWithTag("tab-SEARCH").performClick()
        capture("search")
    }

    @Test fun viewer() {
        state("viewer")
        val gallery = ScreenshotFixtures.gallery()
        show(gallery.copy(selectedMedia = gallery.items[0]))
        capture("viewer")
    }

    @Test fun viewerDetails() {
        state("viewer-details")
        val gallery = ScreenshotFixtures.gallery()
        show(gallery.copy(selectedMedia = gallery.items[0]))
        compose.openViewerDetails()
        capture("viewer-details")
    }

    @Test fun filterSheet() {
        state("filter-sheet")
        show()
        compose.onNodeWithContentDescription(context.getString(R.string.filter_and_sort)).performClick()
        capture("filter-sheet")
    }

    @Test fun activitySheet() {
        state("activity-sheet")
        show()
        compose.onNodeWithTag("activity-button").performClick()
        capture("activity-sheet")
    }

    @Test fun profile() {
        state("profile")
        // The account button asks the view model to open the account; here that state is set directly.
        show(session = ScreenshotFixtures.signedIn.copy(isAccountOpen = true))
        compose.onNodeWithTag("alba-page-scroll").assertExists()
        capture("profile")
    }

    @Test fun signIn() {
        state("sign-in")
        // The account screen opens from state, as the view model opens it for sign-in.
        show(GalleryState(), ScreenshotFixtures.signedOut.copy(isAccountOpen = true))
        capture("sign-in")
    }
}

/** A Pixel 9 class phone, light theme: every state. */
@Config(qualifiers = STANDARD)
class StandardScreenshots : AppScreenshots("standard")

/** A small phone (360 × 640 dp). */
@Config(qualifiers = "w360dp-h640dp-port-hdpi")
class CompactScreenshots : AppScreenshots("compact",
    states = setOf("gallery", "gallery-selection", "empty-signed-out", "viewer", "viewer-details", "filter-sheet", "profile"))

/** A tablet or unfolded window in landscape, where navigation moves to a rail. */
@Config(qualifiers = "w900dp-h600dp-land-hdpi")
class WideScreenshots : AppScreenshots("wide", states = setOf("gallery", "collections", "viewer", "viewer-details", "profile"))

@Config(qualifiers = STANDARD)
class DarkScreenshots : AppScreenshots("dark", darkTheme = true,
    states = setOf("gallery", "gallery-selection", "viewer", "filter-sheet", "profile", "sign-in"))

/** The largest common accessibility text size short of the system maximum. */
@Config(qualifiers = STANDARD)
class LargeFontScreenshots : AppScreenshots("font-1.3", fontScale = 1.3f,
    states = setOf("gallery", "empty-signed-out", "filter-sheet", "profile", "sign-in"))

private const val STANDARD = "en-rUS-w411dp-h914dp-port-hdpi"
