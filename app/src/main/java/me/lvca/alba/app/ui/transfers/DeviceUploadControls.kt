package me.lvca.alba.app.ui.transfers

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.messages.localizedStoredMessage
import me.lvca.alba.app.ui.designsystem.AlbaCard
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.localized
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.TransferViewState

@Composable
internal fun DeviceUploadControls(
    uploadTransfer: TransferView?,
    isSignedIn: Boolean,
    demo: Boolean = false,
    isWorking: Boolean,
    onRequestUpload: () -> Unit,
    onContinuePastUnresolved: () -> Unit,
    onMoveVerifiedPhotoToTrash: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    if (uploadTransfer == null) {
        if (isSignedIn || demo) {
            Button(
                onClick = onRequestUpload,
                enabled = !isWorking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isWorking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(AlbaIcons.CloudUpload, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(if (isWorking) stringResource(R.string.staging_original) else stringResource(if (demo) R.string.demo_upload_title else R.string.upload_to_icloud))
            }
        } else {
            OutlinedButton(
                onClick = onOpenAccount,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sign_in_to_upload))
            }
        }
        return
    }

    Column {
        // Verified status is already shown in the metadata card above these actions.
        if (uploadTransfer.state != TransferViewState.VERIFIED) {
            AlbaCard(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = uploadTransfer.state.icon(),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uploadTransfer.state.label(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = uploadTransfer.message?.let { localizedStoredMessage(it).localized() } ?: stringResource(R.string.staged_bytes, Formatter.formatShortFileSize(LocalContext.current, uploadTransfer.byteCount)),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    if (uploadTransfer.state == TransferViewState.NEEDS_SIGN_IN) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onOpenAccount,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Lock, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.open_icloud_account))
                        }
                    }
                }
            }
        }
        if (uploadTransfer.state == TransferViewState.VERIFIED) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onMoveVerifiedPhotoToTrash,
                enabled = !isWorking,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("move-phone-original-to-trash"),
            ) {
                Icon(AlbaIcons.DeleteSweep, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (isWorking) stringResource(R.string.rechecking_exact_originals) else stringResource(R.string.move_phone_original_to_trash))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.alba_rechecks_the_current_phone_and_icloud_bytes_first_android_then_asks_you_to_confi),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (uploadTransfer.state == TransferViewState.UNRESOLVED) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onContinuePastUnresolved,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.keep_this_item_blocked_continue_others))
            }
        }
    }
}
