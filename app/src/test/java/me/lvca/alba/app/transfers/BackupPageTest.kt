package me.lvca.alba.app.transfers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
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
import me.lvca.alba.app.ui.account.BackupDisclosure
import me.lvca.alba.app.ui.account.DeviceFoldersPage
import me.lvca.alba.app.ui.account.ICloudAccountScreen
import me.lvca.alba.app.ui.account.ProfilePage
import me.lvca.alba.app.ui.account.backedUpFolderNames
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.gallery.BackupBadge
import me.lvca.alba.app.ui.gallery.BackupStatusChip
import me.lvca.alba.app.ui.gallery.LocalBackupBadges
import me.lvca.alba.app.ui.gallery.MediaTile
import me.lvca.alba.engine.model.BackupDraftId
import me.lvca.alba.engine.model.BackupLibrarySummary
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.BackupSettings
import me.lvca.alba.engine.model.BackupView
import me.lvca.alba.engine.model.DeviceFolder
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.LARGE_ITEM_BYTES
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.engine.model.MotionPhotoMode
import me.lvca.alba.engine.model.ProtocolArea
import me.lvca.alba.engine.model.ProtocolPause
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.TransferFailureReason
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.TransferViewState
import me.lvca.alba.protocol.account.ICloudAccountState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun theBackupPageSaysWhatBackedUpMeansAndThatEveryByteIsCheckedBeforeAPhoneCopyIsRemoved() {
        render(TransferState(backup = on))
        compose.onNodeWithTag("backup-how-it-works").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.backup_how_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.backup_how_body)).assertIsDisplayed()
    }

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
    fun aRemotePauseShowsTheMaintainersMessageOrAlbasOwn() {
        val pause = ProtocolPause("upload-change", setOf(ProtocolArea.UPLOADS))
        val queued = listOf(TransferView(TransferId("a"), "device:1", "beach.jpg", TransferViewState.WAITING, 1, 1, automatic = true))
        val state = mutableStateOf(TransferState(backup = on.copy(protocolPause = pause), queue = queued))
        renderState(state)

        compose.onNodeWithText(context.getString(R.string.backup_chip_paused)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.backup_paused_icloud_change_detail)).assertIsDisplayed()

        state.value = state.value.copy(backup = on.copy(protocolPause = pause.copy(messages = mapOf("en" to "Uploads are paused until Friday."))))
        compose.onNodeWithText("Uploads are paused until Friday.").assertIsDisplayed()
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
    fun failuresAreGroupedByReasonEachWithTheActionThatFixesIt() {
        val now = System.currentTimeMillis()
        val queue = listOf(
            TransferView(TransferId("retry"), "device:1", "beach.jpg", TransferViewState.WAITING, 1, 1, automatic = true,
                message = StoredMessage.UPLOAD_INTERRUPTED_TRYING_AGAIN.code, failureReason = TransferFailureReason.WAITING_TO_RETRY,
                nextTryAtEpochMillis = now + 15 * 60_000L),
            TransferView(TransferId("video"), "device:2", "concert.mp4", TransferViewState.WAITING, LARGE_ITEM_BYTES + 1, 1, automatic = true),
            TransferView(TransferId("gone"), "device:3", "deleted.jpg", TransferViewState.NEEDS_ATTENTION, 1, 1, automatic = true,
                retryable = true, message = StoredMessage.THIS_FILE_IS_NO_LONGER_ON_THE_PHONE.code, failureReason = TransferFailureReason.FILE_GONE),
            TransferView(TransferId("access"), "device:4", "a.jpg", TransferViewState.NEEDS_ATTENTION, 1, 1, automatic = true,
                retryable = true, message = StoredMessage.AUTOMATIC_BACKUP_PERMISSION_OR_CONSENT_CHANGED.code,
                failureReason = TransferFailureReason.PHOTO_ACCESS),
            TransferView(TransferId("checking"), "device:5", "b.jpg", TransferViewState.UNRESOLVED, 1, 1, automatic = true,
                failureReason = TransferFailureReason.CHECKING_ICLOUD),
        )
        var retriedNow = 0
        var granted = 0
        var checked = 0
        val dismissed = mutableListOf<List<TransferId>>()
        render(
            TransferState(backup = on, queue = queue), network = NetworkStatus(true, metered = true),
            retryWaitingNow = { retriedNow++ }, dismissFailed = { dismissed += it }, enableAutomaticBackup = { granted++ },
            checkICloudNow = { checked++ },
        )

        listOf("waiting-to-retry", "needs-unmetered", "photo-access", "file-gone", "checking-icloud").forEach { reason ->
            compose.onNodeWithTag("backup-failure-group-$reason").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("backup-failure-group-format-not-supported").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.backup_failure_needs_unmetered)).performScrollTo().assertIsDisplayed()
        val at = android.text.format.DateUtils.formatDateTime(context, now + 15 * 60_000L, android.text.format.DateUtils.FORMAT_SHOW_TIME)
        compose.onNodeWithTag("backup-failure-detail-retry").performScrollTo()
            .assertTextEquals(context.getString(R.string.backup_next_try, at))

        compose.onNodeWithTag("backup-failure-action-waiting-to-retry").performScrollTo()
            .assertTextEquals(context.getString(R.string.backup_fix_retry_now)).performClick()
        compose.onNodeWithTag("backup-failure-action-photo-access").performScrollTo()
            .assertTextEquals(context.getString(R.string.backup_fix_grant_access)).performClick()
        compose.onNodeWithTag("backup-failure-action-file-gone").performScrollTo()
            .assertTextEquals(context.getString(R.string.backup_fix_remove)).performClick()
        compose.onNodeWithTag("backup-failure-action-checking-icloud").performScrollTo()
            .assertTextEquals(context.getString(R.string.backup_fix_check_now)).performClick()
        compose.onNodeWithTag("backup-failure-action-needs-unmetered").performScrollTo()
            .assertTextEquals(context.getString(R.string.backup_fix_wifi_settings))
        assertEquals(1, retriedNow)
        assertEquals(1, granted)
        assertEquals(listOf(listOf(TransferId("gone"))), dismissed)
        assertEquals(1, checked)
    }

    @Test
    fun aLargeVideoOnWifiIsNotAFailure() {
        val video = TransferView(TransferId("video"), "device:2", "concert.mp4", TransferViewState.WAITING, LARGE_ITEM_BYTES + 1, 1, automatic = true)
        render(TransferState(backup = on, queue = listOf(video)))
        compose.onNodeWithTag("backup-failure-group-needs-unmetered").assertDoesNotExist()
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
    fun onlyWhileChargingIsOffUntilTurnedOnAndSaysWhyBackupWaits() {
        val saved = mutableListOf<BackupSettings>()
        val waiting = TransferView(TransferId("a"), "device:1", "a.jpg", TransferViewState.WAITING, 1, 1, automatic = true)
        render(TransferState(backup = on, queue = listOf(waiting)), setBackupSettings = { saved += it }, charging = false)
        compose.onNodeWithTag("backup-only-while-charging").performScrollTo().assertIsOff().performClick()
        assertEquals(listOf(BackupSettings(onlyWhileCharging = true)), saved)
        compose.onNodeWithTag("backup-headline").assertTextEquals(context.resources.getQuantityString(R.plurals.backup_chip_backing_up, 1, 1))
    }

    @Test
    fun motionPhotosIsPhotoOnlyUntilTheUserPicksAnotherOptionInItsDialog() {
        val saved = mutableListOf<BackupSettings>()
        render(TransferState(backup = on), setBackupSettings = { saved += it })
        compose.onNodeWithTag("motion-photos-setting").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.backup_motion_photos_photo_only)).assertIsDisplayed()

        compose.onNodeWithTag("motion-photos-setting").performClick()
        compose.onNodeWithTag("motion-photos-option-photo-only").assertIsSelected()
        compose.onNodeWithTag("motion-photos-option-photo-and-video").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.backup_motion_photos_live)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.backup_motion_photos_live_detail)).assertIsDisplayed()
        compose.onNodeWithTag("motion-photos-option-live-photo").performClick()

        assertEquals(listOf(BackupSettings(motionPhotos = MotionPhotoMode.LIVE_PHOTO)), saved)
        compose.onNodeWithTag("motion-photos-options").assertDoesNotExist()
    }

    @Test
    fun theChosenMotionPhotoOptionIsShownAndSelected() {
        render(TransferState(backup = on, backupSettings = BackupSettings(motionPhotos = MotionPhotoMode.PHOTO_AND_VIDEO)))
        compose.onNodeWithText(context.getString(R.string.backup_motion_photos_separately)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("motion-photos-setting").performClick()
        compose.onNodeWithTag("motion-photos-option-photo-and-video").assertIsSelected()
    }

    @Test
    fun theBackupPageSaysWaitingToChargeWhenOnlyTheChargerHoldsBackupBack() {
        val waiting = TransferView(TransferId("a"), "device:1", "a.jpg", TransferViewState.WAITING, 1, 1, automatic = true)
        render(TransferState(backup = on, queue = listOf(waiting), backupSettings = BackupSettings(onlyWhileCharging = true)), charging = false)
        compose.onNodeWithTag("backup-headline").assertTextEquals(context.getString(R.string.backup_status_charging))
        compose.onNodeWithTag("backup-only-while-charging").performScrollTo().assertIsOn()
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
    fun backupOfNewItemsOnlyOffersToIncludeExistingOnesAfterAsking() {
        var included = 0
        render(TransferState(backup = on), includeExistingInBackup = { included++ })
        compose.onNodeWithTag("backup-include-existing").performScrollTo().performClick()
        assertEquals("Nothing starts before the user confirms", 0, included)
        compose.onNodeWithText(context.getString(R.string.backup_existing_confirm_body, on.destinationLabel)).assertIsDisplayed()
        compose.onNodeWithTag("backup-include-existing-confirm").performClick()
        assertEquals(1, included)
    }

    @Test
    fun backupThatIncludesExistingItemsDoesNotOfferItAgain() {
        render(TransferState(backup = on.copy(includesExisting = true)))
        compose.onNodeWithTag("backup-include-existing").assertDoesNotExist()
    }

    @Test
    fun theDisclosureBacksUpExistingItemsByDefaultAndCanBeNarrowed() {
        val accepted = mutableListOf<Boolean>()
        compose.setContent {
            AlbaTheme(darkTheme = false) {
                BackupDisclosure(BackupSetup(BackupDraftId("draft"), "alex@example.com"), storageFull = false,
                    accept = { accepted += it }, dismiss = {})
            }
        }
        compose.onNodeWithTag("backup-choice-existing").assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.backup_existing_choice_all_detail)).assertIsDisplayed()
        compose.onNodeWithTag("backup-disclosure-accept").performClick()
        compose.onNodeWithTag("backup-choice-new-only").performClick()
        compose.onNodeWithTag("backup-choice-new-only").assertIsSelected()
        compose.onNodeWithTag("backup-disclosure-accept").performClick()
        assertEquals(listOf(true, false), accepted)
    }

    @Test
    fun theDisclosureNamesHowManyPhotosAndVideosBackingUpAllCovers() {
        var setup by mutableStateOf(BackupSetup(BackupDraftId("draft"), "alex@example.com"))
        compose.setContent {
            AlbaTheme(darkTheme = false) {
                BackupDisclosure(setup, storageFull = false, accept = {}, dismiss = {})
            }
        }
        // Until MediaStore is counted, or without full access to count it, no number is named.
        compose.onNodeWithText("Back up all photos and videos").assertIsDisplayed()
        compose.onNodeWithText("Only new photos and videos").assertIsDisplayed()

        setup = setup.copy(library = BackupLibrarySummary(items = 1_234, bytes = 5_000_000_000L))
        val size = android.text.format.Formatter.formatShortFileSize(context, 5_000_000_000L)
        compose.onNodeWithText("Back up all 1,234 photos and videos ($size)").assertIsDisplayed()
        compose.onNodeWithTag("backup-choice-existing").assertIsSelected()
    }

    @Test
    fun theBackupPageNamesTheFoldersThatBackUp() {
        val folders = listOf(
            DeviceFolder("device-folder:external_primary:1", "Camera", "DCIM/Camera/", 10, isCamera = true),
            DeviceFolder("device-folder:external_primary:2", "Screenshots", "Pictures/Screenshots/", 4, isCamera = false, isScreenshots = true),
            DeviceFolder("device-folder:external_primary:3", "WhatsApp Images", "Pictures/WhatsApp Images/", 7, isCamera = false),
            DeviceFolder("device-folder:external_primary:4", "Downloads", "Download/", 2, isCamera = false),
        )
        val settings = BackupSettings(deviceFolders = setOf(DeviceFolder.SCREENSHOTS, "device-folder:external_primary:3"))
        render(TransferState(backup = on, backupSettings = settings, deviceFolders = folders))
        compose.onNodeWithText("Camera, ${context.getString(R.string.backup_screenshots_folder)}, WhatsApp Images")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun folderNamesWaitForThePhonesFoldersAndNameScreenshotsBeforeTheyExist() {
        assertNull(backedUpFolderNames(setOf(DeviceFolder.SCREENSHOTS), null, "Screenshots"))
        val camera = DeviceFolder("device-folder:external_primary:1", "Camera", "DCIM/Camera/", 1, isCamera = true)
        assertEquals(listOf("Camera", "Screenshots"), backedUpFolderNames(setOf(DeviceFolder.SCREENSHOTS), listOf(camera), "Screenshots"))
        assertEquals(listOf("Camera"), backedUpFolderNames(emptySet(), listOf(camera), "Screenshots"))
    }

    @Test
    fun turningOffAScreenshotsFolderTurnsOffScreenshotsEverywhere() {
        val saved = mutableListOf<BackupSettings>()
        val shots = DeviceFolder("device-folder:external_primary:2", "Screenshots", "Pictures/Screenshots/", 4, isCamera = false, isScreenshots = true)
        val settings = BackupSettings(deviceFolders = setOf(DeviceFolder.SCREENSHOTS))
        compose.setContent {
            AlbaTheme(darkTheme = false) {
                androidx.compose.foundation.layout.Column {
                    DeviceFoldersPage(TransferState(backup = on, backupSettings = settings, deviceFolders = listOf(shots)),
                        testAlbaActions(setBackupSettings = { saved += it }))
                }
            }
        }
        compose.onNodeWithTag("backup-folder-Screenshots").assertIsOn().performClick()
        assertEquals(listOf(BackupSettings(deviceFolders = emptySet())), saved)
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

    private fun renderState(transfers: androidx.compose.runtime.State<TransferState>) {
        compose.setContent {
            AlbaTheme(darkTheme = false) {
                ICloudAccountScreen(
                    session = SessionState(accountState = account, network = NetworkStatus(true, false), requestedPage = ProfilePage.BACKUP),
                    transfers = transfers.value,
                    phoneStorage = PhoneStorageState(),
                    mediaBusy = false,
                    diagnostics = DiagnosticsConsent(),
                    actions = testAlbaActions(),
                )
            }
        }
        compose.onNodeWithTag("backup-status").assertExists()
    }

    private fun render(
        transfers: TransferState,
        network: NetworkStatus = NetworkStatus(true, false),
        charging: Boolean? = null,
        retryTransfer: (TransferId) -> Unit = {},
        retryAll: () -> Unit = {},
        setBackupSettings: (BackupSettings) -> Unit = {},
        retryAfterStorageFull: () -> Unit = {},
        includeExistingInBackup: () -> Unit = {},
        retryWaitingNow: () -> Unit = {},
        dismissFailed: (List<TransferId>) -> Unit = {},
        enableAutomaticBackup: () -> Unit = {},
        checkICloudNow: () -> Unit = {},
    ) {
        compose.setContent {
            AlbaTheme(darkTheme = false) {
                ICloudAccountScreen(
                    session = SessionState(accountState = account, network = network, charging = charging, requestedPage = ProfilePage.BACKUP),
                    transfers = transfers,
                    phoneStorage = PhoneStorageState(),
                    mediaBusy = false,
                    diagnostics = DiagnosticsConsent(),
                    actions = testAlbaActions(retryTransfer = retryTransfer, retryAllTransfers = retryAll,
                        setBackupSettings = setBackupSettings, retryAfterStorageFull = retryAfterStorageFull,
                        includeExistingInBackup = includeExistingInBackup, retryWaitingNow = retryWaitingNow,
                        dismissFailedTransfers = dismissFailed, enableAutomaticBackup = enableAutomaticBackup,
                        checkICloudNow = checkICloudNow),
                )
            }
        }
        compose.onNodeWithTag("backup-status").assertExists()
    }
}
