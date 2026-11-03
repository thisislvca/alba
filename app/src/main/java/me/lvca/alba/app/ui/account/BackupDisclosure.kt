package me.lvca.alba.app.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.transfers.BackupSetup

/**
 * What turning on backup does, and whether it covers what is already on the phone. Backing up existing photos and
 * videos is chosen by default, as in Google Photos; "only new ones" is the narrower choice.
 */
@Composable
internal fun BackupDisclosure(
    setup: BackupSetup,
    storageFull: Boolean,
    accept: (includeExisting: Boolean) -> Unit,
    dismiss: () -> Unit,
) {
    var includeExisting by rememberSaveable(setup.draftId.value) { mutableStateOf(setup.includeExisting) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(if (setup.demo) R.string.back_up_new_photos else R.string.backup_disclosure_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (setup.demo) stringResource(R.string.demo_backup_disclosure)
                    else stringResource(R.string.backup_disclosure, setup.destinationLabel))
                // Google Photos backs up the whole library by default; "only new" is the narrower choice.
                if (!setup.demo) Column(Modifier.selectableGroup()) {
                    BackupExistingChoice(stringResource(R.string.backup_existing_choice_all), stringResource(R.string.backup_existing_choice_all_detail),
                        selected = includeExisting, tag = "backup-choice-existing") { includeExisting = true }
                    BackupExistingChoice(stringResource(R.string.backup_existing_choice_new), null,
                        selected = !includeExisting, tag = "backup-choice-new-only") { includeExisting = false }
                }
                // Each upload is verified by downloading it back, so the whole library costs about twice its size.
                if (!setup.demo && includeExisting) {
                    Text(stringResource(R.string.backup_existing_data_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("backup-existing-data-note"))
                }
                if (!setup.demo) Text(stringResource(R.string.backup_disclosure_notifications), style = MaterialTheme.typography.bodySmall)
                // Warn before the user commits: with a full iCloud nothing can back up until there is space.
                if (storageFull && !setup.demo) Text(stringResource(R.string.backup_disclosure_storage_full),
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("backup-storage-full-warning"))
            }
        },
        confirmButton = {
            Button(onClick = { accept(includeExisting) }, modifier = Modifier.testTag("backup-disclosure-accept")) {
                Text(stringResource(R.string.welcome_continue))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = dismiss, modifier = Modifier.testTag("backup-disclosure-dismiss")) { Text(stringResource(R.string.not_now)) }
        },
    )
}

@Composable
private fun BackupExistingChoice(title: String, detail: String?, selected: Boolean, tag: String, choose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = choose).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
