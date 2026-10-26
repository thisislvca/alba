package me.lvca.alba.app.ui.account

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.demo.DemoTransferMode
import me.lvca.alba.app.messages.localizedStoredMessage
import me.lvca.alba.app.platform.PHONE_TRASH_LIMIT
import me.lvca.alba.app.transfers.TransferState
import me.lvca.alba.app.ui.AccountScreenActions
import me.lvca.alba.app.ui.designsystem.AlbaCard
import me.lvca.alba.app.ui.designsystem.AlbaChevron
import me.lvca.alba.app.ui.designsystem.AlbaDivider
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.AlbaListRow
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing
import me.lvca.alba.app.ui.localized
import me.lvca.alba.app.ui.timestampLabel
import me.lvca.alba.app.ui.transfers.TransferQueueCard
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.model.TransferViewState
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus
import java.time.Instant

/** How many failed or skipped items the Backup page lists before "Retry all" covers the rest. */
private const val FAILED_ROWS = 50

/**
 * The Backup page: on or off, what is backed up and what waits and why, failed and skipped items with Retry, the
 * queue, settings, "Free up space" and iCloud storage. Google Photos' backup page is the reference.
 */
@Composable
internal fun BackupPage(
    session: SessionState,
    transfers: TransferState,
    actions: AccountScreenActions,
    onNavigate: (ProfilePage) -> Unit,
) {
    val state = session.accountState
    val demo = state == ICloudAccountState.Demo
    val signedIn = state as? ICloudAccountState.SignedIn
    val connected = signedIn?.status == SessionStatus.VERIFIED
    val backup = transfers.backup
    val status = backupStatus(transfers, state, session.network)
    val verified = backupItems(transfers, demo).count { it.state == TransferViewState.VERIFIED }
    LaunchedEffect(backup.enabled, verified) { actions.refreshBackupStatus() }

    if (demo) {
        Text(stringResource(R.string.demo_backup_disclosure), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
    }
    BackupStatusCard(session, transfers, status, actions, onNavigate)

    Spacer(Modifier.height(AlbaSpacing.xl))
    ProfileStorage(session, demo = demo, hasAccount = connected, check = actions::checkConnection, labelRes = R.string.cloud_storage)
    if (backup.enabled) {
        Spacer(Modifier.height(AlbaSpacing.xl))
        FailedItems(transfers, demo, actions)
        val inFlight = transfers.queue.filter { it.state in PendingTransferStates }
        if (inFlight.isNotEmpty() || (demo && transfers.queue.isNotEmpty())) {
            TransferQueueCard(if (demo) transfers.queue else inFlight, actions::demoBackupAction, demo = demo,
                onRetry = actions::retryTransfer, title = stringResource(R.string.backup_queue_title))
            if (!demo && inFlight.any { !it.automatic }) {
                OutlinedButton(onClick = actions::cancelUploads, modifier = Modifier.fillMaxWidth().testTag("backup-cancel-uploads")) {
                    Text(stringResource(R.string.cancel_uploads))
                }
            }
        }
    }

    if (!demo) {
        Spacer(Modifier.height(AlbaSpacing.xl))
        AlbaCard {
            BackupRow(stringResource(R.string.free_up_space_title), stringResource(R.string.free_up_space_row), AlbaIcons.DeleteSweep,
                "backup-free-up-space") { onNavigate(ProfilePage.FREE_UP_SPACE) }
        }
        Spacer(Modifier.height(AlbaSpacing.xl))
        BackupSettingsCard(transfers, backup.enabled && backup.scope == BackupScope.CAMERA_MEDIA, actions, onNavigate)
    }

    if (backup.enabled) {
        Spacer(Modifier.height(AlbaSpacing.lg))
        OutlinedButton(onClick = actions::disableAutomaticBackup, modifier = Modifier.fillMaxWidth().testTag("backup-turn-off")) {
            Text(stringResource(R.string.turn_off_automatic_backup))
        }
    }
    if (demo) DemoControls(session, transfers, actions)
}

/** On or off, the status and why backup waits, progress, the last backup, and the one action that helps. */
@Composable
private fun BackupStatusCard(
    session: SessionState,
    transfers: TransferState,
    status: BackupStatus,
    actions: AccountScreenActions,
    onNavigate: (ProfilePage) -> Unit,
) {
    val demo = session.accountState == ICloudAccountState.Demo
    val signedIn = session.accountState as? ICloudAccountState.SignedIn
    val connected = signedIn?.status == SessionStatus.VERIFIED
    val backup = transfers.backup
    val items = backupItems(transfers, demo)
    AlbaCard(modifier = Modifier.testTag("backup-status")) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(backup.enabled, enabled = connected || demo || backup.enabled, role = Role.Switch) { on ->
                        if (on) actions.enableAutomaticBackup() else actions.disableAutomaticBackup()
                    }
                    .testTag("backup-switch"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (demo) R.string.demo_backup_title else R.string.backup_title),
                        style = MaterialTheme.typography.titleMedium)
                    val offDetail = if (demo) R.string.demo_backup_off else R.string.off_you_can_still_upload_one_photo_at_a_time_from_the_system_photo_picker
                    Text(
                        if (backup.enabled) backup.destinationLabel ?: signedIn?.appleId.orEmpty() else stringResource(offDetail),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(backup.enabled, null, enabled = connected || demo || backup.enabled)
            }
            if (!backup.enabled) {
                backup.message?.let { Text(localizedStoredMessage(it).localized(), style = MaterialTheme.typography.bodyMedium) }
                Button(enabled = connected || demo, onClick = actions::enableAutomaticBackup, modifier = Modifier.fillMaxWidth().testTag("backup-start")) {
                    Text(stringResource(if (demo) R.string.demo_backup_start else R.string.set_up_automatic_backup))
                }
            } else {
                AlbaDivider()
                StatusLine(status)
                transfers.backupProgress?.takeIf { it.eligible > 0 && !demo }?.let { progress ->
                    Text(stringResource(R.string.backup_progress, progress.backedUp, progress.eligible), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("backup-progress"))
                    LinearProgressIndicator(
                        progress = { progress.backedUp.toFloat() / progress.eligible },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                items.mapNotNull { it.verifiedAtEpochMillis }.maxOrNull()?.let { last ->
                    Text(stringResource(R.string.backup_last_backed_up, timestampLabel(Instant.ofEpochMilli(last))),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusAction(status, actions, onNavigate)
            }
        }
    }
}

@Composable
private fun StatusLine(status: BackupStatus) {
    val icon: ImageVector = when (status) {
        BackupStatus.UpToDate -> AlbaIcons.CloudDone
        is BackupStatus.BackingUp -> AlbaIcons.CloudUpload
        is BackupStatus.WaitingForWifi, BackupStatus.Offline, BackupStatus.Paused -> AlbaIcons.CloudQueue
        else -> AlbaIcons.CloudOff
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(22.dp),
            tint = if (icon == AlbaIcons.CloudOff) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Column {
            Text(status.headline(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("backup-headline"))
            statusDetail(status)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Why backup waits, in plain words, when the headline alone does not say it. */
@Composable
private fun statusDetail(status: BackupStatus): String? = when (status) {
    is BackupStatus.WaitingForWifi -> pluralStringResource(R.plurals.backup_detail_wifi, status.remaining, status.remaining)
    BackupStatus.SignInNeeded -> stringResource(R.string.backup_paused_sign_in_body)
    BackupStatus.StorageFull -> stringResource(R.string.backup_paused_storage_body)
    BackupStatus.NeedsPermission -> stringResource(R.string.message_backup_paused_allow_full_photo_and_media_location_access)
    BackupStatus.NeedsConsent -> stringResource(R.string.backup_review_detail)
    BackupStatus.Paused -> stringResource(R.string.backup_paused_detail)
    else -> null
}

/** The one thing the user can do about the current status, if any. */
@Composable
private fun StatusAction(status: BackupStatus, actions: AccountScreenActions, onNavigate: (ProfilePage) -> Unit) {
    when (status) {
        BackupStatus.SignInNeeded -> Button(onClick = { onNavigate(ProfilePage.SIGN_IN) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sign_in_to_icloud))
        }
        BackupStatus.StorageFull -> Button(onClick = actions::retryAfterStorageFull, modifier = Modifier.fillMaxWidth().testTag("backup-storage-retry")) {
            Text(stringResource(R.string.backup_storage_try_again))
        }
        BackupStatus.NeedsConsent -> Button(onClick = actions::enableAutomaticBackup, modifier = Modifier.fillMaxWidth().testTag("backup-review")) {
            Text(stringResource(R.string.backup_review_button))
        }
        else -> Unit
    }
}

/** Failed and skipped backup items with their reasons, Retry where nothing was sent, and Retry all. */
@Composable
private fun FailedItems(transfers: TransferState, demo: Boolean, actions: AccountScreenActions) {
    if (demo) return
    val failed = transfers.queue.filter { it.state in FailedTransferStates }
    if (failed.isEmpty()) return
    TransferQueueCard(failed.take(FAILED_ROWS), actions::continuePastUnresolved, onRetry = actions::retryTransfer,
        title = pluralStringResource(R.plurals.backup_failed_title, failed.size, failed.size))
    if (failed.any { it.retryable }) {
        Button(onClick = actions::retryAllTransfers, modifier = Modifier.fillMaxWidth().testTag("backup-retry-all")) {
            Text(stringResource(R.string.retry_all))
        }
        Spacer(Modifier.height(AlbaSpacing.md))
    }
}

@Composable
private fun BackupSettingsCard(transfers: TransferState, folders: Boolean, actions: AccountScreenActions, onNavigate: (ProfilePage) -> Unit) {
    val settings = transfers.backupSettings
    Text(stringResource(R.string.backup_settings_section), Modifier.padding(start = AlbaSpacing.lg, bottom = AlbaSpacing.sm),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    AlbaCard {
        SettingSwitch(stringResource(R.string.backup_photos_mobile_data), stringResource(R.string.backup_mobile_data_detail),
            settings.photosOnMobileData, "backup-photos-mobile") { actions.setBackupSettings(settings.copy(photosOnMobileData = it)) }
        AlbaDivider(Modifier.padding(horizontal = AlbaSpacing.lg))
        SettingSwitch(stringResource(R.string.backup_videos_mobile_data), null,
            settings.videosOnMobileData, "backup-videos-mobile") { actions.setBackupSettings(settings.copy(videosOnMobileData = it)) }
        AlbaDivider(Modifier.padding(horizontal = AlbaSpacing.lg))
        SettingSwitch(stringResource(R.string.backup_while_roaming), stringResource(R.string.backup_while_roaming_detail),
            settings.whileRoaming, "backup-roaming", enabled = settings.usesMobileData) { actions.setBackupSettings(settings.copy(whileRoaming = it)) }
        AlbaDivider(Modifier.padding(horizontal = AlbaSpacing.lg))
        SettingSwitch(stringResource(R.string.backup_pause), stringResource(R.string.backup_pause_detail),
            settings.paused, "backup-pause") { actions.setBackupSettings(settings.copy(paused = it)) }
        if (folders) {
            AlbaDivider(Modifier.padding(horizontal = AlbaSpacing.lg))
            BackupRow(stringResource(R.string.backup_device_folders),
                pluralStringResource(R.plurals.backup_device_folders_detail, settings.deviceFolders.size, settings.deviceFolders.size),
                AlbaIcons.PhotoLibrary, "backup-device-folders") { onNavigate(ProfilePage.BACKUP_FOLDERS) }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, detail: String?, checked: Boolean, tag: String, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = change)
            .padding(horizontal = AlbaSpacing.lg, vertical = 10.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, null, enabled = enabled)
    }
}

@Composable
private fun BackupRow(title: String, subtitle: String, icon: ImageVector, tag: String, onClick: () -> Unit) {
    AlbaListRow(title, onClick, Modifier.testTag(tag), subtitle = subtitle,
        leading = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }, trailing = { AlbaChevron(Modifier.size(18.dp)) })
}

/** Google Photos' "Back up device folders": the camera always, and any other folder the user turns on. */
@Composable
internal fun DeviceFoldersPage(transfers: TransferState, actions: AccountScreenActions) {
    LaunchedEffect(Unit) { actions.loadDeviceFolders() }
    val settings = transfers.backupSettings
    Text(stringResource(R.string.backup_device_folders_intro), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(AlbaSpacing.lg))
    val folders = transfers.deviceFolders
    if (folders == null) {
        Text(stringResource(R.string.checking), style = MaterialTheme.typography.bodyMedium)
        return
    }
    if (folders.isEmpty()) {
        Text(stringResource(R.string.backup_device_folders_empty), style = MaterialTheme.typography.bodyMedium)
        return
    }
    AlbaCard(modifier = Modifier.testTag("backup-folder-list")) {
        folders.forEachIndexed { index, folder ->
            if (index > 0) AlbaDivider(Modifier.padding(horizontal = AlbaSpacing.lg))
            val chosen = folder.isCamera || folder.id in settings.deviceFolders
            SettingSwitch(
                title = if (folder.isCamera) stringResource(R.string.backup_camera_folder, folder.name) else folder.name,
                detail = pluralStringResource(R.plurals.backup_folder_items, folder.itemCount, folder.itemCount),
                checked = chosen,
                tag = "backup-folder-${folder.name}",
                enabled = !folder.isCamera,
            ) { on ->
                actions.setBackupSettings(settings.copy(deviceFolders = if (on) settings.deviceFolders + folder.id else settings.deviceFolders - folder.id))
            }
        }
    }
}

/**
 * "Free up space": how many backed-up phone items and how much space can go, a batch re-check with progress, then
 * Android's own trash request. Nothing leaves the phone without that request, and items stay in Android's trash.
 */
@Composable
internal fun FreeUpSpacePage(transfers: TransferState, actions: AccountScreenActions) {
    LaunchedEffect(Unit) { actions.loadFreeUpSpace() }
    val context = LocalContext.current
    val state = transfers.freeUpSpace
    val candidates = state.candidates
    Text(stringResource(R.string.free_up_space_intro), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(AlbaSpacing.lg))
    AlbaCard(modifier = Modifier.testTag("free-up-space")) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                candidates == null -> Text(stringResource(R.string.checking), style = MaterialTheme.typography.bodyMedium)
                candidates.mediaIds.isEmpty() -> Text(stringResource(R.string.free_up_space_nothing), style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("free-up-space-empty"))
                else -> {
                    // One run frees at most PHONE_TRASH_LIMIT items: show what this run frees, and say when more remain.
                    val now = candidates.mediaIds.size.coerceAtMost(PHONE_TRASH_LIMIT)
                    val bytes = Formatter.formatShortFileSize(context,
                        candidates.byteCounts.take(now).sum().takeIf { candidates.byteCounts.isNotEmpty() } ?: candidates.byteCount)
                    Text(pluralStringResource(R.plurals.free_up_space_summary, now, now, bytes),
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("free-up-space-summary"))
                    val more = candidates.mediaIds.size - now
                    if (more > 0) {
                        Text(pluralStringResource(R.plurals.free_up_space_more_remain, more, more), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("free-up-space-more"))
                    }
                    if (!state.checking) {
                        var confirm by remember { mutableStateOf(false) }
                        Button(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth().testTag("free-up-space-start")) {
                            Text(stringResource(R.string.free_up_space_button, bytes))
                        }
                        if (confirm) AlertDialog(
                            onDismissRequest = { confirm = false },
                            title = { Text(stringResource(R.string.free_up_space_title)) },
                            text = { Text(stringResource(R.string.free_up_space_confirm)) },
                            confirmButton = { TextButton(onClick = { confirm = false; actions.freeUpSpace(candidates.mediaIds) },
                                modifier = Modifier.testTag("free-up-space-confirm")) { Text(stringResource(R.string.welcome_continue)) } },
                            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
                        )
                    }
                }
            }
        }
    }
}

/** The demo's scenario controls, so reviewers can see an interruption, a Wi-Fi wait and the clean-up preview. */
@Composable
private fun DemoControls(session: SessionState, transfers: TransferState, actions: AccountScreenActions) {
    val backup = transfers.backup
    Spacer(Modifier.height(18.dp))
    Text(stringResource(R.string.demo_sync_description), style = MaterialTheme.typography.bodyMedium)
    session.lastRefreshedAt?.let {
        Text(stringResource(R.string.library_refreshed_at, timestampLabel(it)), style = MaterialTheme.typography.bodySmall)
    }
    OutlinedButton(onClick = actions::checkConnection, enabled = !session.isRefreshing, modifier = Modifier.fillMaxWidth().testTag("demo-sync-refresh")) {
        Text(stringResource(R.string.demo_sync_refresh))
    }
    if (!backup.enabled) return
    var confirmCleanup by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { actions.demoBackupAction(TransferId(DemoTransferMode.CONTROL_INTERRUPT)) },
        modifier = Modifier.fillMaxWidth().testTag("demo-backup-interrupt")) {
        Text(stringResource(R.string.demo_backup_interrupt))
    }
    OutlinedButton(onClick = { actions.demoBackupAction(TransferId(DemoTransferMode.CONTROL_WIFI)) },
        modifier = Modifier.fillMaxWidth().testTag("demo-backup-pause-wifi")) {
        Text(stringResource(R.string.demo_backup_pause_wifi))
    }
    OutlinedButton(onClick = { confirmCleanup = true }, modifier = Modifier.fillMaxWidth().testTag("demo-cleanup-preview"),
        enabled = transfers.queue.isNotEmpty() && transfers.queue.all { row -> row.state == TransferViewState.VERIFIED }) {
        Text(stringResource(R.string.demo_cleanup_preview))
    }
    if (confirmCleanup) AlertDialog(onDismissRequest = { confirmCleanup = false },
        title = { Text(stringResource(R.string.demo_cleanup_preview)) },
        text = { Text(stringResource(R.string.demo_cleanup_disclosure)) },
        confirmButton = {
            TextButton(onClick = { confirmCleanup = false; actions.demoBackupAction(TransferId(DemoTransferMode.CONTROL_CLEANUP)) },
                modifier = Modifier.testTag("demo-cleanup-confirm")) {
                Text(stringResource(R.string.welcome_continue))
            }
        },
        dismissButton = { TextButton(onClick = { confirmCleanup = false }) { Text(stringResource(R.string.not_now)) } })
}
