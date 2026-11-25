package me.lvca.alba.app.gallery

import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.R
import me.lvca.alba.app.platform.NetworkStatus
import me.lvca.alba.app.screenshots.ScreenshotFixtures
import me.lvca.alba.app.testing.setAlbaContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Small controls in the library header still offer Material's 48dp touch target. */
@RunWith(AndroidJUnit4::class)
class HeaderTouchTargetsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun SemanticsNodeInteraction.assertTarget(name: String) {
        val size = fetchSemanticsNode().size
        val (width, height) = with(compose.density) { size.width.toDp() to size.height.toDp() }
        assertTrue("$name is $width × $height", width >= 48.dp && height >= 48.dp)
    }

    @Test fun dateHeaderSelectCirclesAreFullTargets() {
        val gallery = ScreenshotFixtures.gallery()
        compose.setAlbaContent(gallery.copy(selection = setOf(gallery.items[0].id)), ScreenshotFixtures.signedIn)
        compose.onAllNodes(hasContentDescription(context.getString(R.string.select_all_shown), substring = true)).onFirst()
            .assertTarget("a date header's select circle")
    }

    @Test fun theAccountButtonCarryingTheStatusIsAFullTarget() {
        // Offline, the account's state is marked on the avatar, and the avatar is the button to the account.
        compose.setAlbaContent(ScreenshotFixtures.gallery(), ScreenshotFixtures.signedIn.copy(network = NetworkStatus(online = false, metered = false)))
        compose.onNodeWithTag("account-status-indicator", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("account-button").assertTarget("the account button")
    }
}
