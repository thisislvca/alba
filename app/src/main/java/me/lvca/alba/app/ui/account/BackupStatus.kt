package me.lvca.alba.app.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import me.lvca.alba.app.R
import me.lvca.alba.app.platform.NetworkStatus
import me.lvca.alba.app.platform.backupNetwork
import me.lvca.alba.app.transfers.TransferState
import me.lvca.alba.engine.model.BackupNetwork
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.TransferViewState
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus

/** What backup is doing, and why it waits, as the Backup page, the gallery chip and notifications word it. */
sealed interface BackupStatus {
    data object Off : BackupStatus

    /** Backup is on, but what it covers changed and the user has not agreed to it yet. */
    data object NeedsConsent : BackupStatus
    data object SignInNeeded : BackupStatus
    data object StorageFull : BackupStatus
    data object NeedsPermission : BackupStatus
    data object Paused : BackupStatus
    data object Offline : BackupStatus
    data class WaitingForWifi(val remaining: Int) : BackupStatus
    data class BackingUp(val remaining: Int) : BackupStatus

    /** Nothing is left to do except items that could not be backed up. */
    data class Failed(val count: Int) : BackupStatus
    data object UpToDate : BackupStatus
}

/** States that mean an item still needs the user: it failed, was refused, or its upload could not be confirmed. */
internal val FailedTransferStates = setOf(TransferViewState.NEEDS_ATTENTION, TransferViewState.UNSUPPORTED, TransferViewState.UNRESOLVED)

/** States that mean an item is still on its way to iCloud. */
internal val PendingTransferStates = setOf(TransferViewState.WAITING, TransferViewState.UPLOADING, TransferViewState.CHECKING_ICLOUD)

/** The backup items, automatic ones only; the demo's sample rows all count. */
internal fun backupItems(transfers: TransferState, demo: Boolean): List<TransferView> =
    transfers.queue.filter { demo || it.automatic }

/** Derives the status from the journal, the account, the network and the user's settings. */
internal fun backupStatus(
    transfers: TransferState,
    accountState: ICloudAccountState,
    network: NetworkStatus?,
): BackupStatus {
    val backup = transfers.backup
    val demo = accountState == ICloudAccountState.Demo
    val session = (accountState as? ICloudAccountState.SignedIn)?.status
    return when {
        !backup.enabled -> BackupStatus.Off
        session == SessionStatus.EXPIRED || session == SessionStatus.PHOTOS_NOT_ENABLED -> BackupStatus.SignInNeeded
        backup.storageFullSinceEpochMillis != null -> BackupStatus.StorageFull
        !backup.disclosureCurrent && !demo -> BackupStatus.NeedsConsent
        backup.message == StoredMessage.BACKUP_PAUSED_ALLOW_FULL_PHOTO_AND_MEDIA_LOCATION_ACCESS.code -> BackupStatus.NeedsPermission
        transfers.backupSettings.paused -> BackupStatus.Paused
        else -> progressStatus(transfers, demo, network)
    }
}

/** Once nothing pauses backup: what is left, whether the network lets it run, and what failed. */
private fun progressStatus(transfers: TransferState, demo: Boolean, network: NetworkStatus?): BackupStatus {
    val items = backupItems(transfers, demo)
    val pending = items.count { it.state in PendingTransferStates }
    val failed = items.count { it.state in FailedTransferStates }
    // Items on the phone that discovery has not reached yet still wait, even with an empty queue.
    val undiscovered = transfers.backupProgress?.let { (it.eligible - it.backedUp - pending - failed).coerceAtLeast(0) } ?: 0
    val remaining = pending + undiscovered
    // The network matters only while something waits; an unknown network says nothing.
    val waitsOn = network?.takeIf { remaining > 0 && !demo }?.backupNetwork
    return when {
        waitsOn == BackupNetwork.OFFLINE -> BackupStatus.Offline
        waitsOn != null && !transfers.backupSettings.allowsAny(waitsOn) -> BackupStatus.WaitingForWifi(remaining)
        demo && items.any { it.message == "demo-wifi" } -> BackupStatus.WaitingForWifi(remaining)
        remaining > 0 -> BackupStatus.BackingUp(remaining)
        failed > 0 -> BackupStatus.Failed(failed)
        else -> BackupStatus.UpToDate
    }
}

/** The status in a few words, for the gallery chip; null when there is nothing to say. */
@Composable
internal fun BackupStatus.chipLabel(): String? = when (this) {
    BackupStatus.Off, BackupStatus.UpToDate -> null
    BackupStatus.NeedsConsent -> stringResource(R.string.backup_chip_review)
    BackupStatus.SignInNeeded -> stringResource(R.string.backup_chip_sign_in)
    BackupStatus.StorageFull -> stringResource(R.string.backup_chip_storage_full)
    BackupStatus.NeedsPermission -> stringResource(R.string.backup_chip_permission)
    BackupStatus.Paused -> stringResource(R.string.backup_chip_paused)
    BackupStatus.Offline -> stringResource(R.string.backup_chip_offline)
    is BackupStatus.WaitingForWifi -> stringResource(R.string.backup_chip_wifi)
    is BackupStatus.BackingUp -> pluralStringResource(R.plurals.backup_chip_backing_up, remaining, remaining)
    is BackupStatus.Failed -> pluralStringResource(R.plurals.backup_chip_failed, count, count)
}

/** The status as the Backup page's headline. */
@Composable
internal fun BackupStatus.headline(): String = when (this) {
    BackupStatus.Off -> stringResource(R.string.backup_status_off)
    BackupStatus.NeedsConsent -> stringResource(R.string.backup_status_review)
    BackupStatus.SignInNeeded -> stringResource(R.string.backup_paused_sign_in_title)
    BackupStatus.StorageFull -> stringResource(R.string.backup_paused_storage_title)
    BackupStatus.NeedsPermission -> stringResource(R.string.backup_status_permission)
    BackupStatus.Paused -> stringResource(R.string.backup_chip_paused)
    BackupStatus.Offline -> stringResource(R.string.backup_chip_offline)
    is BackupStatus.WaitingForWifi -> stringResource(R.string.backup_status_wifi)
    is BackupStatus.BackingUp -> pluralStringResource(R.plurals.backup_chip_backing_up, remaining, remaining)
    is BackupStatus.Failed -> pluralStringResource(R.plurals.backup_chip_failed, count, count)
    BackupStatus.UpToDate -> stringResource(R.string.backup_status_done)
}
