package me.lvca.alba.app.ui.gallery

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.transfers.TransferState
import me.lvca.alba.app.ui.account.BackupStatus
import me.lvca.alba.app.ui.account.backupStatus
import me.lvca.alba.app.ui.account.chipLabel
import me.lvca.alba.app.ui.designsystem.AlbaIcons

/**
 * A compact backup status under the gallery header, shown only when there is something to say: backing up, waiting,
 * paused, storage full, or items that can't be backed up. Tapping it opens the Backup page.
 */
@Composable
internal fun BackupStatusChip(session: SessionState, transfers: TransferState, openBackup: () -> Unit, modifier: Modifier = Modifier) {
    val status = backupStatus(transfers, session.accountState, session.network)
    val label = status.chipLabel() ?: return
    val problem = status is BackupStatus.Failed || status == BackupStatus.StorageFull || status == BackupStatus.SignInNeeded ||
        status == BackupStatus.NeedsPermission
    AssistChip(
        onClick = openBackup,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            Icon(
                when {
                    problem -> AlbaIcons.CloudOff
                    status is BackupStatus.BackingUp -> AlbaIcons.CloudUpload
                    else -> AlbaIcons.CloudQueue
                },
                contentDescription = null,
                tint = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        modifier = modifier.padding(horizontal = 16.dp).testTag("backup-chip"),
    )
}
