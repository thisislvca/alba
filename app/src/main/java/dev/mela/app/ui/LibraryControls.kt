package dev.mela.app.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import dev.mela.app.R
import dev.mela.app.GalleryUiState
import dev.mela.engine.companion.BatchAction
import dev.mela.engine.model.*
import dev.mela.protocol.account.ICloudAccountState
import java.time.Instant
import java.time.ZoneOffset

@Composable
internal fun SelectionActions(state: GalleryUiState, selectAll: () -> Unit, clear: () -> Unit,
    batch: (BatchAction) -> Unit, actions: LibraryActions) {
    var addingToAlbum by rememberSaveable { mutableStateOf(false) }
    val editable = state.canEditLibrary()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        if (state.selection.isNotEmpty()) {
            val selectedItems = state.items.filter { it.id in state.selection }
            val hasCloud = selectedItems.any { it.origin == MediaOrigin.ICLOUD }
            val hasShared = selectedItems.any { it.isShared }
            val hasPhone = selectedItems.any { it.origin == MediaOrigin.DEVICE }
            var more by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(androidx.compose.ui.res.pluralStringResource(R.plurals.selected_count, state.selection.size, state.selection.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = selectAll) { Text(stringResource(R.string.select_all_shown)) }
                IconButton(onClick = clear) { Icon(androidx.compose.material.icons.Icons.Outlined.Close, stringResource(R.string.cancel_selection)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(enabled = state.activeDownloadId == null && selectedItems.size <= 50,
                    onClick = { actions.share(selectedItems) }) { Text(stringResource(R.string.share)) }
                if (hasCloud) FilledTonalButton(onClick = { batch(BatchAction.KEEP_OFFLINE) }) { Text(stringResource(R.string.keep_offline)) }
                if (hasCloud && !state.query.trashOnly && android.os.Build.VERSION.SDK_INT >= 29)
                    FilledTonalButton(onClick = { batch(BatchAction.SAVE_TO_PHONE) }, modifier = Modifier.testTag("save-selection-to-phone")) {
                        Text(stringResource(R.string.save_to_phone_gallery))
                    }
                if (hasPhone && !state.query.trashOnly && selectedItems.any { it.origin == MediaOrigin.DEVICE && dev.mela.engine.source.UploadMediaFormat.isCandidate(it.mimeType) }) FilledTonalButton(enabled = (state.accountState as? ICloudAccountState.SignedIn)?.status == dev.mela.protocol.account.SessionStatus.VERIFIED,
                    onClick = { batch(BatchAction.UPLOAD) }) { Text(stringResource(R.string.upload_phone_jpegs)) }
                Box {
                    OutlinedButton(onClick = { more = true }) { Text(stringResource(R.string.more)) }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        if (!state.query.trashOnly && !hasShared) {
                            val canFavorite = !state.accountBusy && state.activeDownloadId == null && (!hasCloud || editable) && selectedItems.size <= 50
                            DropdownMenuItem(text = { Text(stringResource(R.string.favorite)) }, enabled = canFavorite,
                                onClick = { more = false; actions.favorites(selectedItems.map { it.id }, true) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.unfavorite)) }, enabled = canFavorite,
                                onClick = { more = false; actions.favorites(selectedItems.map { it.id }, false) })
                        }
                        if (android.os.Build.VERSION.SDK_INT >= 30 && hasPhone && !hasCloud) DropdownMenuItem(
                            text = { Text(stringResource(if (state.query.trashOnly) R.string.restore_photo else R.string.trash_phone_copy)) },
                            onClick = { more = false; actions.trash(selectedItems, state.query.trashOnly) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.add_to_album)) }, enabled = editable && !hasShared && !state.query.trashOnly && hasCloud && !hasPhone && selectedItems.size <= 50,
                            onClick = { more = false; addingToAlbum = true })
                        val album = state.collections.firstOrNull { it.id == state.query.collectionId && it.shared == null && !it.isFolder && it.shared == null && !it.id.startsWith("smart:") && !it.id.startsWith("device-folder:") }
                        if (album != null && !state.query.trashOnly) DropdownMenuItem(text = { Text(stringResource(R.string.remove_from_album)) },
                            enabled = editable && hasCloud && !hasPhone && selectedItems.size <= 50,
                            onClick = { more = false; actions.removeFromAlbum(album.id, selectedItems.map { it.id }) })
                        if (hasCloud && !hasPhone && !hasShared) DropdownMenuItem(text = { Text(stringResource(if (state.query.trashOnly) R.string.restore_from_icloud else R.string.trash_in_icloud)) },
                            enabled = editable && selectedItems.size <= 50,
                            onClick = { more = false; actions.cloudTrash(selectedItems.map { it.id }, !state.query.trashOnly) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.remove_offline_copies)) }, enabled = hasCloud,
                            onClick = { more = false; batch(BatchAction.REMOVE_CACHE) })
                    }
                }
            }
            if (selectedItems.size > 50) Text(stringResource(R.string.selection_action_limit), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (addingToAlbum) AlbumChooser(state.collections, dismiss = { addingToAlbum = false }, create = { name ->
        actions.createAlbumWithPhotos(name, state.selection.toList()); addingToAlbum = false
    }) { id ->
        actions.addToAlbum(id, state.selection.toList()); addingToAlbum = false
    }
}

@Composable
internal fun BatchProgress(state: GalleryUiState, retry: (String) -> Unit, cancel: (String) -> Unit = {}) {
    var expandedBatch by rememberSaveable { mutableStateOf<String?>(null) }
    var visibleBatchItems by rememberSaveable(expandedBatch) { mutableIntStateOf(50) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.batches.groupBy { it.batchId }.entries.toList().takeLast(3).forEach { (id, items) ->
            val done = items.count { it.state == "DONE" }
            val failed = items.count { it.state in setOf("FAILED", "NEEDS_ATTENTION", "STOPPED") }
            val action = stringResource(when (items.first().action) {
                "KEEP_OFFLINE" -> R.string.keep_offline
                "REMOVE_CACHE" -> R.string.remove_offline_copies
                "SAVE_TO_PHONE" -> R.string.save_to_phone_gallery
                else -> R.string.upload_photos
            })
            Text(stringResource(if (items.first().action in setOf("UPLOAD", "PICKER_UPLOAD")) R.string.batch_queued else R.string.batch_done, action, done, items.size))
            if (failed > 0) Text(androidx.compose.ui.res.pluralStringResource(R.plurals.attention_count, failed, failed))
            items.filter { it.state in setOf("FAILED", "NEEDS_ATTENTION", "STOPPED") }.take(3).forEach { Text(stringResource(R.string.batch_item_progress, it.mediaId.substringAfterLast('/'), dev.mela.app.localizedStoredMessage(it.message).localized()), style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { expandedBatch = if (expandedBatch == id) null else id }) {
                Text(if (expandedBatch == id) stringResource(R.string.hide_item_progress) else stringResource(R.string.show_item_progress))
            }
            if (expandedBatch == id) {
                items.take(visibleBatchItems).forEach { item ->
                    val name = state.items.firstOrNull { it.id == item.mediaId }?.fileName
                        ?: item.mediaId.substringAfterLast('/').substringAfterLast(':')
                    val progress = when (item.state) {
                        "CANCELED" -> stringResource(R.string.download_canceled)
                        "WAITING" -> stringResource(R.string.waiting)
                        "RUNNING" -> stringResource(R.string.batch_in_progress)
                        "DONE" -> item.message?.let { dev.mela.app.localizedStoredMessage(it).localized() } ?: stringResource(R.string.completed)
                        else -> item.message?.let { dev.mela.app.localizedStoredMessage(it).localized() } ?: stringResource(R.string.needs_attention)
                    }
                    Text(stringResource(R.string.batch_item_progress, name, progress), style = MaterialTheme.typography.bodySmall)
                }
                if (items.size > visibleBatchItems) TextButton(onClick = { visibleBatchItems += 50 }) { Text(stringResource(R.string.show_more_items)) }
            }
            if (items.any { it.state == "FAILED" }) TextButton(onClick = { retry(id) }) { Text(stringResource(R.string.retry_failed_items)) }
            if (items.any { it.state == "CANCELED" }) Text(stringResource(R.string.download_canceled_completed_kept), style = MaterialTheme.typography.bodySmall)
            if (items.first().action in setOf("KEEP_OFFLINE", "SAVE_TO_PHONE") && items.any { it.state in setOf("WAITING", "RUNNING") })
                TextButton(onClick = { cancel(id) }, modifier = Modifier.testTag("cancel-batch-$id")) { Text(stringResource(R.string.cancel_download)) }
        }
    }
}

internal fun GalleryUiState.canEditLibrary() = (accountState == ICloudAccountState.Demo ||
    (accountState as? ICloudAccountState.SignedIn)?.status == dev.mela.protocol.account.SessionStatus.VERIFIED) &&
    activeDownloadId == null && !accountBusy

@Composable
internal fun ICloudIcon(@androidx.annotation.DrawableRes resource: Int) {
    Icon(painterResource(resource), contentDescription = null, tint = androidx.compose.ui.graphics.Color(0xFF009FFF), modifier = Modifier.size(24.dp))
}

internal fun SmartCollection.icon(): Int = when (this) {
    SmartCollection.VIDEOS -> R.drawable.icloud_videos
    SmartCollection.LIVE_PHOTOS -> R.drawable.icloud_live_photos
    SmartCollection.SCREENSHOTS -> R.drawable.icloud_screenshots
    SmartCollection.PANORAMAS -> R.drawable.icloud_panoramas
    SmartCollection.BURSTS -> R.drawable.icloud_bursts
    SmartCollection.SLO_MO -> R.drawable.icloud_slo_mo
    SmartCollection.TIME_LAPSE -> R.drawable.icloud_time_lapse
}

@Composable
internal fun AlbumNameDialog(title: String, initialName: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        OutlinedTextField(name, { if (it.length <= 255) name = it }, label = { Text(stringResource(R.string.album_name)) }, singleLine = true)
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { save(name.trim()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumChooser(collections: List<GalleryCollection>, dismiss: () -> Unit, create: (String) -> Unit, choose: (String) -> Unit) {
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating) AlbumNameDialog(stringResource(R.string.new_album), "", { creating = false }) { name ->
        creating = false; create(name)
    }
    MelaBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            MelaSheetHeading(stringResource(R.string.add_to_album), dismiss = dismiss)
            val albums = collections.filter { !it.isFolder && it.shared == null && !it.id.startsWith("smart:") && !it.id.startsWith("device-folder:") }
            TextButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.new_album))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), contentPadding = PaddingValues(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
                items(albums, key = { it.id }) { album ->
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = { choose(album.id) }) {
                        ICloudIcon(R.drawable.icloud_albums)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(album.name)
                            collections.firstOrNull { it.id == album.parentId }?.let { Text(it.name, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}
