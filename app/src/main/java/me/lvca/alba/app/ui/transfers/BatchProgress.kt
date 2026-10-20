package me.lvca.alba.app.ui.transfers

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.items
import me.lvca.alba.engine.model.*
import androidx.compose.ui.res.pluralStringResource
import me.lvca.alba.app.R
import me.lvca.alba.app.messages.localizedStoredMessage
import me.lvca.alba.app.ui.localized

@Composable
internal fun BatchProgress(batches: List<BatchItem>, items: List<GalleryMedia>, retry: (String) -> Unit, cancel: (String) -> Unit = {}) {
    var expandedBatch by rememberSaveable { mutableStateOf<String?>(null) }
    var visibleBatchItems by rememberSaveable(expandedBatch) { mutableIntStateOf(50) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        batches.groupBy { it.batchId }.entries.toList().takeLast(3).forEach { (id, rows) ->
            val done = rows.count { it.status == BatchItemState.DONE }
            val failed = rows.count { it.status in AttentionStates }
            val action = stringResource(when (rows.first().action) {
                "KEEP_OFFLINE" -> R.string.keep_offline
                "REMOVE_CACHE" -> R.string.remove_offline_copies
                "SAVE_TO_PHONE" -> R.string.save_to_phone_gallery
                else -> R.string.upload_photos
            })
            Text(stringResource(if (rows.first().action in setOf("UPLOAD", "PICKER_UPLOAD")) R.string.batch_queued else R.string.batch_done, action, done, rows.size))
            if (failed > 0) Text(pluralStringResource(R.plurals.attention_count, failed, failed))
            rows.filter { it.status in AttentionStates }.take(3).forEach { Text(stringResource(R.string.batch_item_progress, it.mediaId.substringAfterLast('/'), localizedStoredMessage(it.message).localized()), style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { expandedBatch = if (expandedBatch == id) null else id }) {
                Text(if (expandedBatch == id) stringResource(R.string.hide_item_progress) else stringResource(R.string.show_item_progress))
            }
            if (expandedBatch == id) {
                rows.take(visibleBatchItems).forEach { item ->
                    val name = items.firstOrNull { it.id == item.mediaId }?.fileName
                        ?: item.mediaId.substringAfterLast('/').substringAfterLast(':')
                    val progress = when (item.status) {
                        BatchItemState.CANCELED -> stringResource(R.string.download_canceled)
                        BatchItemState.WAITING -> stringResource(R.string.waiting)
                        BatchItemState.RUNNING -> stringResource(R.string.batch_in_progress)
                        BatchItemState.DONE -> item.message?.let { localizedStoredMessage(it).localized() } ?: stringResource(R.string.completed)
                        BatchItemState.FAILED, BatchItemState.NEEDS_ATTENTION, BatchItemState.STOPPED ->
                            item.message?.let { localizedStoredMessage(it).localized() } ?: stringResource(R.string.needs_attention)
                    }
                    Text(stringResource(R.string.batch_item_progress, name, progress), style = MaterialTheme.typography.bodySmall)
                }
                if (rows.size > visibleBatchItems) TextButton(onClick = { visibleBatchItems += 50 }) { Text(stringResource(R.string.show_more_items)) }
            }
            if (rows.any { it.status == BatchItemState.FAILED }) TextButton(onClick = { retry(id) }) { Text(stringResource(R.string.retry_failed_items)) }
            if (rows.any { it.status == BatchItemState.CANCELED }) Text(stringResource(R.string.download_canceled_completed_kept), style = MaterialTheme.typography.bodySmall)
            if (rows.first().action in setOf("KEEP_OFFLINE", "SAVE_TO_PHONE") && rows.any { it.status.isPending })
                TextButton(onClick = { cancel(id) }, modifier = Modifier.testTag("cancel-batch-$id")) { Text(stringResource(R.string.cancel_download)) }
        }
    }
}

/** Batch rows that stopped short of completion and need the user's attention. */
private val AttentionStates = setOf(BatchItemState.FAILED, BatchItemState.NEEDS_ATTENTION, BatchItemState.STOPPED)
