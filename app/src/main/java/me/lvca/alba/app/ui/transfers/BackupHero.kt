package me.lvca.alba.app.ui.transfers

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import me.lvca.alba.app.R
import me.lvca.alba.app.transfers.TransferState
import me.lvca.alba.app.ui.account.BackupStatus
import me.lvca.alba.app.ui.account.PendingTransferStates
import me.lvca.alba.app.ui.account.backupItems
import me.lvca.alba.app.ui.account.headline
import me.lvca.alba.app.ui.account.statusDetail
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.AlbaListGroup
import me.lvca.alba.app.ui.designsystem.AlbaListItem
import me.lvca.alba.app.ui.designsystem.theme.AlbaIconSize
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing
import me.lvca.alba.engine.model.TransferViewState

/**
 * How far backup has come, from 0 to 1: what is verified of everything backup covers once that is counted, or of the
 * queue until then. Null when there is nothing to measure.
 */
internal fun backupFraction(transfers: TransferState, demo: Boolean): Float? {
    transfers.backupProgress?.takeIf { it.eligible > 0 && !demo }?.let { return (it.backedUp.toFloat() / it.eligible).coerceIn(0f, 1f) }
    val items = backupItems(transfers, demo)
    val done = items.count { it.state == TransferViewState.VERIFIED }
    val total = done + items.count { it.state in PendingTransferStates }
    return if (total == 0) null else done.toFloat() / total
}

/**
 * The Activity sheet's first word on backup, Google Photos style: backed up and when, backing up with what is left and
 * a progress bar, what it waits for, or off or stuck with the one button that fixes it. [now] is the clock "Last backup"
 * is measured against.
 */
@Composable
internal fun BackupHero(
    status: BackupStatus,
    transfers: TransferState,
    demo: Boolean,
    canTurnOn: Boolean,
    onAction: (BackupHeroAction) -> Unit,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    AlbaListGroup(modifier.testTag("backup-hero")) {
        AlbaListItem(verticalArrangement = Arrangement.spacedBy(AlbaSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AlbaSpacing.md)) {
                val problem = status.heroAction(canTurnOn) != null && status != BackupStatus.Off
                Icon(status.heroIcon(), null, Modifier.size(AlbaIconSize.standard), tint = when {
                    problem || status is BackupStatus.Failed -> MaterialTheme.colorScheme.error
                    status == BackupStatus.Off -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                })
                Column(Modifier.weight(1f)) {
                    Text(status.heroTitle(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("backup-hero-title"))
                    heroDetail(status, transfers, demo, now)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("backup-hero-detail"))
                    }
                }
            }
            if (status is BackupStatus.BackingUp) backupFraction(transfers, demo)?.let { fraction ->
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().testTag("backup-progress"))
            }
            status.heroAction(canTurnOn)?.let { action ->
                Button(onClick = { onAction(action) }, modifier = Modifier.fillMaxWidth().testTag("backup-hero-action")) {
                    Text(action.label())
                }
            }
        }
    }
}

private fun BackupStatus.heroIcon(): ImageVector = when (this) {
    BackupStatus.UpToDate -> AlbaIcons.CloudDone
    is BackupStatus.BackingUp -> AlbaIcons.CloudUpload
    is BackupStatus.WaitingForWifi, is BackupStatus.WaitingToCharge, BackupStatus.Offline, BackupStatus.Paused,
    is BackupStatus.PausedForICloudChange -> AlbaIcons.CloudQueue
    else -> AlbaIcons.CloudOff
}

@Composable
private fun BackupStatus.heroTitle(): String = when (this) {
    BackupStatus.UpToDate -> stringResource(R.string.backup_hero_backed_up)
    is BackupStatus.BackingUp -> stringResource(R.string.backup_hero_backing_up)
    BackupStatus.Paused, is BackupStatus.PausedForICloudChange -> stringResource(R.string.backup_hero_paused)
    else -> headline()
}

/** The line under the title: when backup last finished, what is left, or why it waits. */
@Composable
private fun heroDetail(status: BackupStatus, transfers: TransferState, demo: Boolean, now: Long): String? = when (status) {
    BackupStatus.Off -> stringResource(if (demo) R.string.demo_backup_off else R.string.backup_hero_off_detail)
    BackupStatus.UpToDate -> backupItems(transfers, demo).mapNotNull { it.verifiedAtEpochMillis }.maxOrNull()?.let { lastBackupLabel(it, now) }
    is BackupStatus.BackingUp -> leftLabel(status.remaining, transfers, demo)
    is BackupStatus.WaitingForWifi -> leftLabel(status.remaining, transfers, demo)
    is BackupStatus.WaitingToCharge -> leftLabel(status.remaining, transfers, demo)
    else -> statusDetail(status)
}

/** "14 left · 230 MB": the size counts the items already queued, the only ones whose size backup knows yet. */
@Composable
private fun leftLabel(remaining: Int, transfers: TransferState, demo: Boolean): String {
    val bytes = backupItems(transfers, demo).filter { it.state in PendingTransferStates }.sumOf { it.byteCount }
    return if (bytes > 0) {
        pluralStringResource(R.plurals.backup_hero_left, remaining, remaining, Formatter.formatShortFileSize(LocalContext.current, bytes))
    } else pluralStringResource(R.plurals.backup_hero_left_count, remaining, remaining)
}

/** "Last backup 2 min ago", in the phone's language; "just now" within the minute. */
@Composable
internal fun lastBackupLabel(lastEpochMillis: Long, now: Long): String =
    if (now - lastEpochMillis < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.backup_hero_last_backup_now)
    else stringResource(
        R.string.backup_hero_last_backup,
        DateUtils.getRelativeTimeSpanString(lastEpochMillis, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString(),
    )

@Composable
private fun BackupHeroAction.label(): String = stringResource(when (this) {
    BackupHeroAction.TURN_ON -> R.string.backup_hero_turn_on
    BackupHeroAction.SIGN_IN -> R.string.sign_in_to_icloud
    BackupHeroAction.ALLOW_ACCESS -> R.string.backup_hero_allow_access
    BackupHeroAction.RETRY_STORAGE -> R.string.backup_storage_try_again
    BackupHeroAction.REVIEW -> R.string.backup_review_button
})
