package me.lvca.alba.app.transfers

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.DiagnosticsConsent
import me.lvca.alba.app.R
import me.lvca.alba.app.account.PhoneStorageState
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.actions.MediaWork
import me.lvca.alba.app.gallery.GalleryState
import me.lvca.alba.app.platform.NetworkStatus
import me.lvca.alba.app.testing.testAlbaActions
import me.lvca.alba.app.ui.AlbaActions
import me.lvca.alba.app.ui.AlbaApp
import me.lvca.alba.app.ui.account.backupStatus
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.transfers.BackupHero
import me.lvca.alba.app.ui.transfers.BackupHeroAction
import me.lvca.alba.engine.model.BackupProgress
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.BackupSettings
import me.lvca.alba.engine.model.BackupView
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.TransferFailureReason
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.TransferViewState
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Activity sheet opens with backup: one hero per status, from fixed state, with what is left, how far it has come,
 * and the one button that fixes it. The avatar marks the same: a ring while backing up, a badge when stuck.
 */
@RunWith(AndroidJUnit4::class)
class BackupHeroTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val signedIn = ICloudAccountState.SignedIn("alex@example.com")
    private val wifi = NetworkStatus(online = true, metered = false)
    private val on = BackupView(enabled = true, destinationLabel = "alex@example.com", scope = BackupScope.CAMERA_MEDIA)
    private val now = 1_800_000_000_000L
    private val actions = mutableListOf<BackupHeroAction>()

    /** Two photos queued, 230 MB between them, out of 14 still to back up of 20 the phone holds. */
    private val backingUp = TransferState(
        backup = on,
        queue = listOf(item("a", TransferViewState.UPLOADING, 100_000_000), item("b", TransferViewState.WAITING, 130_000_000)),
        backupProgress = BackupProgress(backedUp = 6, eligible = 20, pending = 14),
    )

    @Test
    fun offOffersToTurnBackupOn() {
        render(TransferState())
        title(R.string.backup_status_off)
        detail(context.getString(R.string.backup_hero_off_detail))
        tapAction(R.string.backup_hero_turn_on, BackupHeroAction.TURN_ON)
    }

    @Test
    fun offWithoutAVerifiedAccountOffersSignIn() {
        render(TransferState(), canTurnOn = false)
        title(R.string.backup_status_off)
        tapAction(R.string.sign_in_to_icloud, BackupHeroAction.SIGN_IN)
    }

    @Test
    fun backedUpSaysWhenTheLastBackupFinished() {
        val twoMinutesAgo = now - 2 * 60_000L
        render(TransferState(backup = on, queue = listOf(item("a", TransferViewState.VERIFIED, 1, verifiedAt = twoMinutesAgo))))
        title(R.string.backup_hero_backed_up)
        val relative = DateUtils.getRelativeTimeSpanString(twoMinutesAgo, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        detail(context.getString(R.string.backup_hero_last_backup, relative))
        compose.onNodeWithTag("backup-progress").assertDoesNotExist()
        compose.onNodeWithTag("backup-hero-action").assertDoesNotExist()
    }

    @Test
    fun backedUpWithinTheMinuteSaysJustNow() {
        render(TransferState(backup = on, queue = listOf(item("a", TransferViewState.VERIFIED, 1, verifiedAt = now - 20_000L))))
        detail(context.getString(R.string.backup_hero_last_backup_now))
    }

    @Test
    fun backingUpShowsWhatIsLeftAndHowFarItHasCome() {
        render(backingUp)
        title(R.string.backup_hero_backing_up)
        detail("14 left · ${Formatter.formatShortFileSize(context, 230_000_000)}")
        compose.onNodeWithTag("backup-progress")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.3f, 0f..1f)))
        compose.onNodeWithTag("backup-hero-action").assertDoesNotExist()
    }

    @Test
    fun waitingForWifiSaysSoWithWhatIsLeft() {
        render(backingUp, network = NetworkStatus(online = true, metered = true))
        title(R.string.backup_status_wifi)
        detail("14 left · ${Formatter.formatShortFileSize(context, 230_000_000)}")
        compose.onNodeWithTag("backup-progress").assertDoesNotExist()
    }

    @Test
    fun waitingToChargeSaysSo() {
        render(backingUp.copy(backupSettings = BackupSettings(onlyWhileCharging = true)), charging = false)
        title(R.string.backup_status_charging)
        compose.onNodeWithTag("backup-hero-action").assertDoesNotExist()
    }

    @Test
    fun pausedSaysSo() {
        render(backingUp.copy(backupSettings = BackupSettings(paused = true)))
        title(R.string.backup_hero_paused)
        detail(context.getString(R.string.backup_paused_detail))
    }

    @Test
    fun missingPhotoAccessOffersToAllowIt() {
        render(TransferState(backup = on.copy(message = StoredMessage.BACKUP_PAUSED_ALLOW_FULL_PHOTO_AND_MEDIA_LOCATION_ACCESS.code)))
        title(R.string.backup_status_permission)
        tapAction(R.string.backup_hero_allow_access, BackupHeroAction.ALLOW_ACCESS)
    }

    @Test
    fun anExpiredSignInOffersToSignIn() {
        render(backingUp, account = ICloudAccountState.SignedIn("alex@example.com", SessionStatus.EXPIRED))
        title(R.string.backup_paused_sign_in_title)
        tapAction(R.string.sign_in_to_icloud, BackupHeroAction.SIGN_IN)
    }

    @Test
    fun fullICloudStorageOffersToTryAgain() {
        render(TransferState(backup = on.copy(storageFullSinceEpochMillis = 1)))
        title(R.string.backup_paused_storage_title)
        tapAction(R.string.backup_storage_try_again, BackupHeroAction.RETRY_STORAGE)
    }

    @Test
    fun theActivitySheetLeadsWithBackupAndTurnsItOn() {
        var enabled = 0
        showApp(TransferState(), testAlbaActions(enableAutomaticBackup = { enabled++ }))
        compose.onNodeWithTag("activity-button").performClick()

        compose.onNodeWithTag("backup-hero").assertIsDisplayed()
        // Nothing moved yet: a quiet line under the hero, then uploading by hand and the Backup page.
        compose.onNodeWithTag("activity-empty").assertIsDisplayed()
        compose.onNodeWithTag("upload-picker-button").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("activity-open-backup").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("backup-hero-action").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, enabled) }
    }

    @Test
    fun theActivityHeroGroupsFailuresWithTheirFix() {
        var retriedAll = 0
        val failed = listOf(
            item("a", TransferViewState.UNSUPPORTED, 1).copy(retryable = true, failureReason = TransferFailureReason.FORMAT_NOT_SUPPORTED),
            item("b", TransferViewState.UNSUPPORTED, 1).copy(retryable = true, failureReason = TransferFailureReason.FORMAT_NOT_SUPPORTED),
        )
        showApp(TransferState(backup = on, queue = failed), testAlbaActions(retryAllTransfers = { retriedAll++ }))
        compose.onNodeWithTag("activity-button").performClick()

        compose.onNodeWithTag("backup-failure-group-format-not-supported").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.backup_failure_count, 2, 2)).assertIsDisplayed()
        // The hero names the groups; their items are listed on the Backup page.
        compose.onNodeWithTag("backup-failure-detail-a").assertDoesNotExist()
        compose.onNodeWithTag("backup-failure-action-format-not-supported").performScrollTo()
            .assertTextEquals(context.getString(R.string.backup_fix_try_again)).performClick()
        compose.runOnIdle { assertEquals(1, retriedAll) }
    }

    @Test
    fun theAvatarRingsWhileBackingUpAndIsBadgedWhenBackupNeedsTheUser() {
        var transfers by mutableStateOf(backingUp)
        showApp({ transfers }, testAlbaActions())
        val indicator = compose.onNodeWithTag("account-status-indicator", useUnmergedTree = true)
        indicator.assertContentDescriptionEquals(context.resources.getQuantityString(R.plurals.backup_chip_backing_up, 14, 14))

        transfers = TransferState(backup = on.copy(storageFullSinceEpochMillis = 1))
        indicator.assertContentDescriptionEquals(context.getString(R.string.backup_paused_storage_title))

        transfers = TransferState(backup = on.copy(message = StoredMessage.BACKUP_PAUSED_ALLOW_FULL_PHOTO_AND_MEDIA_LOCATION_ACCESS.code))
        indicator.assertContentDescriptionEquals(context.getString(R.string.backup_status_permission))

        transfers = TransferState(backup = on, queue = listOf(item("a", TransferViewState.VERIFIED, 1, verifiedAt = now)))
        indicator.assertDoesNotExist()
    }

    private fun render(
        transfers: TransferState,
        canTurnOn: Boolean = true,
        account: ICloudAccountState = signedIn,
        network: NetworkStatus = wifi,
        charging: Boolean? = true,
    ) = compose.setContent {
        AlbaTheme(darkTheme = false) {
            BackupHero(backupStatus(transfers, account, network, charging), transfers, demo = false, canTurnOn = canTurnOn,
                onAction = { actions += it }, now = now)
        }
    }

    private fun showApp(transfers: TransferState, actions: AlbaActions) = showApp({ transfers }, actions)

    private fun showApp(transfers: () -> TransferState, actions: AlbaActions) = compose.setContent {
        AlbaTheme(darkTheme = false) {
            AlbaApp(
                gallery = GalleryState(),
                session = SessionState(accountState = signedIn, isRefreshing = false, network = wifi, charging = true),
                transfers = transfers(),
                phoneStorage = PhoneStorageState(),
                mediaWork = MediaWork(),
                previewRetryVersion = 0,
                message = null,
                diagnostics = DiagnosticsConsent(),
                actions = actions,
            )
        }
    }

    private fun title(text: Int) = compose.onNodeWithTag("backup-hero-title").assertTextEquals(context.getString(text))

    private fun detail(text: String) = compose.onNodeWithTag("backup-hero-detail").assertTextEquals(text)

    private fun tapAction(label: Int, expected: BackupHeroAction) {
        compose.onNodeWithTag("backup-hero-action").assertTextEquals(context.getString(label)).performClick()
        compose.runOnIdle { assertEquals(listOf(expected), actions) }
    }

    private fun item(id: String, state: TransferViewState, bytes: Long, verifiedAt: Long? = null) =
        TransferView(TransferId(id), "device:$id", "$id.jpg", state, bytes, 1, verifiedAtEpochMillis = verifiedAt, automatic = true)
}
