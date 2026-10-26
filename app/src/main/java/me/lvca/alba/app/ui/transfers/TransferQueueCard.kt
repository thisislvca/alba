package me.lvca.alba.app.ui.transfers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.messages.localizedStoredMessage
import me.lvca.alba.app.ui.designsystem.AlbaCard
import me.lvca.alba.app.ui.localized
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.TransferViewState

@Composable
internal fun TransferQueueCard(
    transfers: List<TransferView>,
    onContinuePastUnresolved: (TransferId) -> Unit,
    demo: Boolean = false,
    onRetry: (TransferId) -> Unit = {},
    title: String? = null,
) {
    AlbaCard(
        modifier = Modifier
            .padding(bottom = 12.dp)
            .testTag("transfer-queue"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(title ?: stringResource(if (demo) R.string.demo_backup_title else R.string.uploads), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            transfers.forEachIndexed { index, transfer ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = transfer.state.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = transfer.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (demo) stringResource(when {
                                transfer.message == "demo-wifi" -> R.string.demo_backup_wifi
                                transfer.message == "demo-cleaned" -> R.string.demo_cleanup_complete
                                transfer.state == TransferViewState.NEEDS_ATTENTION -> R.string.demo_backup_interrupted
                                else -> when (transfer.state) {
                                TransferViewState.WAITING -> R.string.demo_backup_waiting
                                TransferViewState.UPLOADING -> R.string.demo_backup_copying
                                TransferViewState.CHECKING_ICLOUD -> R.string.demo_backup_checking
                                TransferViewState.VERIFIED -> R.string.demo_backup_complete
                                else -> R.string.demo_backup_off
                            } }) else transfer.message?.let { localizedStoredMessage(it).localized() } ?: transfer.state.label(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (transfer.state == TransferViewState.UNRESOLVED && !demo) {
                    // Never a blind retry: the upload may have reached iCloud, so skipping is the only choice offered.
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.unresolved_item_explanation), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { onContinuePastUnresolved(transfer.id) },
                        modifier = Modifier.fillMaxWidth().testTag("skip-unresolved-${transfer.id.value}"),
                    ) { Text(stringResource(R.string.skip_unresolved_item)) }
                } else if (demo && transfer.message in setOf("demo-interrupted", "demo-wifi")) {
                    Spacer(Modifier.height(9.dp))
                    OutlinedButton(
                        onClick = { onContinuePastUnresolved(transfer.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.demo_backup_retry)) }
                } else if (transfer.retryable && !demo) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { onRetry(transfer.id) },
                        modifier = Modifier.fillMaxWidth().testTag("retry-transfer-${transfer.id.value}"),
                    ) { Text(stringResource(R.string.retry)) }
                }
                if (index != transfers.lastIndex) {
                    Spacer(Modifier.height(9.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(9.dp))
                }
            }
        }
    }
}
