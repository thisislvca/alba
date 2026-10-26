package me.lvca.alba.app.transfers

import androidx.work.NetworkType
import me.lvca.alba.app.TransferPrompt
import me.lvca.alba.app.nextTransferPrompt
import me.lvca.alba.app.platform.MobileDataChoice
import me.lvca.alba.app.platform.NetworkStatus
import me.lvca.alba.app.platform.backupNetwork
import me.lvca.alba.app.platform.notBackedUpCount
import me.lvca.alba.app.ui.account.BackupStatus
import me.lvca.alba.app.ui.account.backupStatus
import me.lvca.alba.app.ui.gallery.BackupBadge
import me.lvca.alba.app.ui.gallery.backupBadges
import me.lvca.alba.app.work.BackupPause
import me.lvca.alba.app.work.backupNetworkType
import me.lvca.alba.app.work.backupPause
import me.lvca.alba.app.work.newlyFailed
import me.lvca.alba.engine.model.BackupNetwork
import me.lvca.alba.engine.model.BackupProgress
import me.lvca.alba.engine.model.BackupSettings
import me.lvca.alba.engine.model.BackupView
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.TransferViewState
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupStatusTest {
    private val signedIn = ICloudAccountState.SignedIn("alex@example.com")
    private val wifi = NetworkStatus(online = true, metered = false)
    private val mobile = NetworkStatus(online = true, metered = true)

    @Test
    fun statusSaysWhyBackupWaitsBeforeItCountsProgress() {
        val on = TransferState(backup = BackupView(enabled = true), queue = listOf(transfer("a", TransferViewState.WAITING)))
        assertEquals(BackupStatus.Off, backupStatus(TransferState(), signedIn, wifi))
        assertEquals(BackupStatus.BackingUp(1), backupStatus(on, signedIn, wifi))
        assertEquals(BackupStatus.WaitingForWifi(1), backupStatus(on, signedIn, mobile))
        assertEquals(BackupStatus.BackingUp(1),
            backupStatus(on.copy(backupSettings = BackupSettings(photosOnMobileData = true)), signedIn, mobile))
        assertEquals(BackupStatus.Offline, backupStatus(on, signedIn, NetworkStatus(online = false, metered = true)))
        assertEquals(BackupStatus.SignInNeeded, backupStatus(on, ICloudAccountState.SignedIn("alex@example.com", SessionStatus.EXPIRED), wifi))
        assertEquals(BackupStatus.StorageFull, backupStatus(on.copy(backup = BackupView(true, storageFullSinceEpochMillis = 1)), signedIn, wifi))
        assertEquals(BackupStatus.NeedsConsent, backupStatus(on.copy(backup = BackupView(true, disclosureCurrent = false)), signedIn, wifi))
        assertEquals(BackupStatus.Paused, backupStatus(on.copy(backupSettings = BackupSettings(paused = true)), signedIn, wifi))
    }

    @Test
    fun failedItemsShowOnlyOnceNothingElseIsLeft() {
        val failed = TransferState(backup = BackupView(enabled = true), queue = listOf(
            transfer("a", TransferViewState.UNSUPPORTED),
            transfer("b", TransferViewState.VERIFIED),
            transfer("c", TransferViewState.WAITING, automatic = false),
        ))
        assertEquals(BackupStatus.Failed(1), backupStatus(failed, signedIn, wifi))
        assertEquals("Items not yet discovered still count as waiting", BackupStatus.BackingUp(2),
            backupStatus(failed.copy(backupProgress = BackupProgress(backedUp = 1, eligible = 4)), signedIn, wifi))
        assertEquals(BackupStatus.UpToDate, backupStatus(failed.copy(queue = listOf(transfer("b", TransferViewState.VERIFIED))), signedIn, wifi))
    }

    @Test
    fun gridBadgesAreExceptionsOnlyAndOnlyWhileBackupIsOn() {
        val uploads = listOf(
            transfer("queued", TransferViewState.UPLOADING), transfer("failed", TransferViewState.NEEDS_ATTENTION),
            transfer("done", TransferViewState.VERIFIED), transfer("skipped", TransferViewState.SKIPPED),
        ).associateBy { requireNotNull(it.mediaId) }
        assertEquals(mapOf("queued" to BackupBadge.QUEUED, "failed" to BackupBadge.FAILED, "done" to BackupBadge.DONE),
            backupBadges(backupEnabled = true, uploads))
        assertEquals(emptyMap<String, BackupBadge>(), backupBadges(backupEnabled = false, uploads))
    }

    @Test
    fun manualTransfersAskAboutNotificationsOnceThenMobileData() {
        val ask = MobileDataChoice.ASK
        assertEquals(TransferPrompt.NOTIFICATIONS, nextTransferPrompt(askNotifications = true, mobile, ask, after = null))
        assertEquals(TransferPrompt.MOBILE_DATA, nextTransferPrompt(askNotifications = true, mobile, ask, after = TransferPrompt.NOTIFICATIONS))
        assertNull(nextTransferPrompt(askNotifications = false, wifi, ask, after = null))
        assertNull(nextTransferPrompt(askNotifications = false, mobile, MobileDataChoice.USE, after = null))
        assertNull(nextTransferPrompt(askNotifications = false, mobile, ask, after = TransferPrompt.MOBILE_DATA))
    }

    @Test
    fun backgroundWorkNeedsOnlyTheNetworksTheUserAllows() {
        assertEquals(NetworkType.UNMETERED, backupNetworkType(BackupSettings()))
        assertEquals(NetworkType.NOT_ROAMING, backupNetworkType(BackupSettings(videosOnMobileData = true)))
        assertEquals(NetworkType.CONNECTED, backupNetworkType(BackupSettings(photosOnMobileData = true, whileRoaming = true)))
        assertEquals(BackupNetwork.ROAMING, NetworkStatus(online = true, metered = true, roaming = true).backupNetwork)
        assertEquals(BackupNetwork.OFFLINE, NetworkStatus(online = false, metered = false).backupNetwork)
    }

    @Test
    fun notificationsReportPausesAndNewFailuresOnly() {
        assertEquals(BackupPause.SIGN_IN, backupPause(enabled = true, signInNeeded = true, storageFullSince = 5))
        assertEquals(BackupPause.STORAGE_FULL, backupPause(enabled = false, signInNeeded = true, storageFullSince = 5))
        assertNull(backupPause(enabled = true, signInNeeded = false, storageFullSince = null))
        val rows = listOf(
            transfer("old", TransferViewState.UNSUPPORTED, updatedAt = 10), transfer("new", TransferViewState.UNRESOLVED, updatedAt = 30),
            transfer("manual", TransferViewState.NEEDS_ATTENTION, updatedAt = 30, automatic = false),
            transfer("skipped", TransferViewState.SKIPPED, updatedAt = 30),
        )
        assertEquals(listOf("new"), newlyFailed(rows, notifiedThrough = 20).map { it.mediaId })
    }

    @Test
    fun deletingPhoneItemsCountsThoseWithoutAVerifiedUpload() {
        val items = listOf(media("a", MediaOrigin.DEVICE), media("b", MediaOrigin.DEVICE), media("c", MediaOrigin.ICLOUD))
        val uploads = mapOf("a" to transfer("a", TransferViewState.VERIFIED), "b" to transfer("b", TransferViewState.WAITING))
        assertEquals(1, notBackedUpCount(items, uploads))
        assertEquals(2, notBackedUpCount(items, emptyMap()))
    }

    private fun transfer(id: String, state: TransferViewState, automatic: Boolean = true, updatedAt: Long = 0) =
        TransferView(TransferId("t-$id"), id, "$id.jpg", state, 1, updatedAt, automatic = automatic,
            verifiedAtEpochMillis = if (state == TransferViewState.VERIFIED) updatedAt else null)

    private fun media(id: String, origin: MediaOrigin) = GalleryMedia(
        id = id, fileName = "$id.jpg", capturedAtEpochMillis = 0, width = 1, height = 1, origin = origin,
        availability = MediaAvailability.DEVICE_ORIGINAL, previewReference = null, originalReference = "content://media/external/images/media/$id",
        accentStartArgb = 0, accentEndArgb = 0, sourceRevision = "r",
    )
}
