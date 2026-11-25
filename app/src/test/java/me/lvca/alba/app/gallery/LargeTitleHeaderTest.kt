package me.lvca.alba.app.gallery

import android.content.Context
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.R
import me.lvca.alba.app.platform.NetworkStatus
import me.lvca.alba.app.screenshots.ScreenshotFixtures
import me.lvca.alba.app.testing.setAlbaContent
import me.lvca.alba.app.transfers.TransferState
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.BackupSettings
import me.lvca.alba.engine.model.BackupView
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Each destination opens with its name as a large title, with no logo or status line, and the account's state is a
 * quiet mark on the avatar that says what it is and leads to the account.
 */
@RunWith(AndroidJUnit4::class)
class LargeTitleHeaderTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val indicator get() = compose.onNodeWithTag("account-status-indicator", useUnmergedTree = true)

    @Test fun photosOpensWithItsNameAndNoLogo() {
        compose.setAlbaContent(ScreenshotFixtures.gallery(), ScreenshotFixtures.signedIn)

        compose.onNodeWithTag("screen-title-LIBRARY").assertTextEquals(context.getString(R.string.gallery_photos)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.brand_name)).assertDoesNotExist()
        // A verified, idle account has nothing to mark.
        indicator.assertDoesNotExist()
    }

    @Test fun collectionsOpensWithItsName() {
        compose.setAlbaContent(ScreenshotFixtures.gallery(), ScreenshotFixtures.signedIn)

        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("screen-title-COLLECTIONS").assertTextEquals(context.getString(R.string.collections)).assertIsDisplayed()
    }

    @Test fun restoringTheSessionShowsASyncRing() {
        compose.setAlbaContent(ScreenshotFixtures.gallery(), ScreenshotFixtures.signedIn.copy(accountState = ICloudAccountState.Restoring))

        indicator.assertIsDisplayed().assertContentDescriptionEquals(context.getString(R.string.restoring_icloud))
    }

    @Test fun offlineIsMarkedOnTheAvatarAndTheMarkOpensTheAccount() {
        var opened = 0
        compose.setAlbaContent(ScreenshotFixtures.gallery(), ScreenshotFixtures.signedIn.copy(network = NetworkStatus(online = false, metered = false)),
            onOpenAccount = { opened++ })

        indicator.assertIsDisplayed().assertContentDescriptionEquals(context.getString(R.string.offline_saved_photos_available))
        compose.onNodeWithTag("account-button").performClick()
        assertEquals(1, opened)
    }

    @Test fun anExpiredSignInIsMarkedAsAnError() {
        compose.setAlbaContent(ScreenshotFixtures.gallery(),
            ScreenshotFixtures.signedIn.copy(accountState = ICloudAccountState.SignedIn("alex@example.com", SessionStatus.EXPIRED)))

        indicator.assertIsDisplayed().assertContentDescriptionEquals(context.getString(R.string.sign_in_again_saved_photos_available))
    }

    @Test fun pausedBackupIsMarkedOnTheAvatar() {
        val paused = TransferState(backup = BackupView(enabled = true, destinationLabel = "alex@example.com", scope = BackupScope.CAMERA_MEDIA),
            backupSettings = BackupSettings(paused = true))
        compose.setAlbaContent(ScreenshotFixtures.gallery(), ScreenshotFixtures.signedIn, transfers = paused)

        indicator.assertIsDisplayed().assertContentDescriptionEquals(context.getString(R.string.backup_chip_paused))
    }

    @Test fun aRefreshOfAStoredLibraryMarksNothing() {
        compose.setAlbaContent(ScreenshotFixtures.gallery(), ScreenshotFixtures.signedIn.copy(isRefreshing = true))

        indicator.assertDoesNotExist()
        compose.onNodeWithTag("catalog-scan-bar").assertDoesNotExist()
    }

    @Test fun theDemoHasNoMark() {
        compose.setAlbaContent(ScreenshotFixtures.gallery(), ScreenshotFixtures.signedIn.copy(accountState = ICloudAccountState.Demo))

        indicator.assertDoesNotExist()
        compose.onNodeWithTag("screen-title-LIBRARY").assertIsDisplayed()
    }
}
