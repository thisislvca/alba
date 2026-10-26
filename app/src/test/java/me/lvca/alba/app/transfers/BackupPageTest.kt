package me.lvca.alba.app.transfers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.lvca.alba.app.DiagnosticsConsent
import me.lvca.alba.app.MobileDataPrompt
import me.lvca.alba.app.R
import me.lvca.alba.app.account.PhoneStorageState
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.platform.NetworkStatus
import me.lvca.alba.app.testing.testAlbaActions
import me.lvca.alba.app.ui.account.ICloudAccountScreen
import me.lvca.alba.app.ui.account.ProfilePage
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.gallery.BackupBadge
import me.lvca.alba.app.ui.gallery.BackupStatusChip
import me.lvca.alba.app.ui.gallery.LocalBackupBadges
import me.lvca.alba.app.ui.gallery.MediaTile
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.BackupSettings
import me.lvca.alba.engine.model.BackupView
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.TransferViewState
import me.lvca.alba.protocol.account.ICloudAccountState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Backup page, the gallery chip, tile badges and the mobile-data prompt, rendered from fixed state. */
@RunWith(AndroidJUnit4::class)
class BackupPageTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val account = ICloudAccountState.SignedIn("alex@example.com")
    private val on = BackupView(enabled = true, destinationLabel = "alex@example.com", scope = BackupScope.CAMERA_MEDIA)

    @Test
    fun storageFullSaysSoAndLetsTheUserTryAgain() {
        var retried = 0
        render(TransferState(backup = on.copy(storageFullSinceEpochMillis = 1)), retryAfterStorageFull = { retried++ })
        compose.onNodeWithTag("backup-headline").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.backup_paused_storage_title)).assertIsDisplayed()
        compose.onNodeWithTag("backup-storage-retry").performScrollTo().performClick()
        assertEquals(1, retried)
    }

    @Test
    fun failedAndSkippedItemsShowReadableReasonsAndRetry() {
        val retried = mutableListOf<TransferId>()
        var retriedAll = 0
        val queue = listOf(
            TransferView(TransferId("avif"), "device:1", "clip.avif", TransferViewState.UNSUPPORTED, 1, 2,
                message = StoredMessage.UPLOAD_AVIF_UNSUPPORTED.code, automatic = true, retryable = true),
            TransferView(TransferId("uncertain"), "device:2", "beach.jpg", TransferViewState.UNRESOLVED, 1, 1, automatic = true),
        )
        render(TransferState(backup = on, queue = queue), retryTransfer = { retried += it }, retryAll = { retriedAll++ })
        compose.onNodeWithText(context.getString(R.string.upload_avif_unsupported)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("retry-transfer-avif").performScrollTo().performClick()
        assertEquals(listOf(TransferId("avif")), retried)
        compose.onNodeWithTag("skip-unresolved-uncertain").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("retry-transfer-uncertain").assertDoesNotExist()
        compose.onNodeWithTag("backup-retry-all").performScrollTo().performClick()
        assertEquals(1, retriedAll)
    }

    @Test
    fun mobileDataSettingsAreOffUntilTurnedOn() {
        val saved = mutableListOf<BackupSettings>()
        render(TransferState(backup = on), setBackupSettings = { saved += it }, network = NetworkStatus(true, metered = true))
        compose.onNodeWithText(context.getString(R.string.backup_status_done)).assertIsDisplayed()
        compose.onNodeWithTag("backup-photos-mobile").performScrollTo().performClick()
        assertEquals(listOf(BackupSettings(photosOnMobileData = true)), saved)
        compose.onNodeWithTag("backup-device-folders").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun chipShowsOnlyWhenThereIsSomethingToSay() {
        var opened = 0
        val item = TransferView(TransferId("a"), "device:1", "a.jpg", TransferViewState.WAITING, 1, 1, automatic = true)
        val waiting = TransferState(backup = on, queue = listOf(item))
        compose.setContent {
            AlbaTheme(darkTheme = false) {
                BackupStatusChip(SessionState(accountState = account, network = NetworkStatus(true, metered = true)), waiting, { opened++ })
            }
        }
        compose.onNodeWithText(context.getString(R.string.backup_chip_wifi)).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun chipHidesWhenEverythingIsBackedUp() {
        compose.setContent {
            AlbaTheme(darkTheme = false) {
                BackupStatusChip(SessionState(accountState = account, network = NetworkStatus(true, false)), TransferState(backup = on), {})
            }
        }
        compose.onNodeWithTag("backup-chip").assertDoesNotExist()
    }

    @Test
    fun tilesBadgeOnlyWaitingAndFailedItems() {
        val badges = mutableStateOf(mapOf("device:1" to BackupBadge.FAILED, "device:2" to BackupBadge.DONE))
        compose.setContent {
            AlbaTheme(darkTheme = false) {
                CompositionLocalProvider(LocalBackupBadges provides badges) {
                    Box(Modifier.size(120.dp)) { MediaTile(media = media("device:1"), onClick = {}, onVisible = {}) }
                    Box(Modifier.size(120.dp)) { MediaTile(media = media("device:2"), onClick = {}, onVisible = {}) }
                }
            }
        }
        compose.onNodeWithTag("backup-badge-failed", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("backup-badge-done", useUnmergedTree = true).assertDoesNotExist()
        badges.value = mapOf("device:1" to BackupBadge.QUEUED)
        compose.onNodeWithTag("backup-badge-queued", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("backup-badge-failed", useUnmergedTree = true).assertDoesNotExist()
        // Finishing while on screen shows cloud-done briefly.
        badges.value = mapOf("device:1" to BackupBadge.DONE)
        compose.onNodeWithTag("backup-badge-done", useUnmergedTree = true).assertExists()
        compose.mainClock.advanceTimeBy(4_000)
        compose.onNodeWithTag("backup-badge-done", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun mobileDataPromptCanRememberTheAnswer() {
        val answers = mutableListOf<Pair<Boolean, Boolean>>()
        compose.setContent { AlbaTheme(darkTheme = false) { MobileDataPrompt(answer = { use, remember -> answers += use to remember }, dismiss = {}) } }
        compose.onNodeWithTag("mobile-data-remember").performClick()
        compose.onNodeWithTag("mobile-data-wait").performClick()
        assertEquals(listOf(false to true), answers)
    }

    private fun media(id: String) = GalleryMedia(id, "$id.jpg", 0, 1, 1, MediaOrigin.DEVICE, MediaAvailability.DEVICE_ORIGINAL,
        null, "content://media/external/images/media/1", 0, 0)

    private fun render(
        transfers: TransferState,
        network: NetworkStatus = NetworkStatus(true, false),
        retryTransfer: (TransferId) -> Unit = {},
        retryAll: () -> Unit = {},
        setBackupSettings: (BackupSettings) -> Unit = {},
        retryAfterStorageFull: () -> Unit = {},
    ) {
        compose.setContent {
            AlbaTheme(darkTheme = false) {
                ICloudAccountScreen(
                    session = SessionState(accountState = account, network = network, requestedPage = ProfilePage.BACKUP),
                    transfers = transfers,
                    phoneStorage = PhoneStorageState(),
                    mediaBusy = false,
                    diagnostics = DiagnosticsConsent(),
                    actions = testAlbaActions(retryTransfer = retryTransfer, retryAllTransfers = retryAll,
                        setBackupSettings = setBackupSettings, retryAfterStorageFull = retryAfterStorageFull),
                )
            }
        }
        compose.onNodeWithTag("backup-status").assertExists()
    }
}
