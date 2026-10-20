package me.lvca.alba.app.ui

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
import me.lvca.alba.app.R
import me.lvca.alba.app.GalleryState
import me.lvca.alba.app.SessionState
import me.lvca.alba.engine.companion.BatchAction
import me.lvca.alba.engine.model.*
import me.lvca.alba.protocol.account.ICloudAccountState
import java.time.Instant
import java.time.ZoneOffset
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material.icons.Icons
import android.os.Build
import me.lvca.alba.engine.source.UploadMediaFormat
import me.lvca.alba.protocol.account.SessionStatus
import me.lvca.alba.app.localizedStoredMessage
import androidx.annotation.DrawableRes
import me.lvca.alba.app.ui.theme.AlbaSpacing
import me.lvca.alba.app.ui.theme.AlbaTheme

@Composable
internal fun SelectionActions(gallery: GalleryState, session: SessionState, mediaBusy: Boolean, actions: GalleryScreenActions) {
    var addingToAlbum by rememberSaveable { mutableStateOf(false) }
    val editable = session.canEditLibrary(mediaBusy)
    val query = gallery.query
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        if (gallery.selection.isNotEmpty()) {
            val selectedItems = gallery.items.filter { it.id in gallery.selection }
            val hasCloud = selectedItems.any { it.origin == MediaOrigin.ICLOUD }
            val hasShared = selectedItems.any { it.isShared }
            val hasPhone = selectedItems.any { it.origin == MediaOrigin.DEVICE }
            val withinLimit = selectedItems.size <= MAX_SELECTION_ACTION_ITEMS
            var more by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.selected_count, gallery.selection.size, gallery.selection.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = actions::selectAllShown) { Text(stringResource(R.string.select_all_shown)) }
                IconButton(onClick = actions::clearSelection) { Icon(Icons.Outlined.Close, stringResource(R.string.cancel_selection)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(enabled = !mediaBusy && withinLimit,
                    onClick = { actions.share(selectedItems) }) { Text(stringResource(R.string.share)) }
                if (hasCloud) FilledTonalButton(onClick = { actions.runBatch(BatchAction.KEEP_OFFLINE) }) { Text(stringResource(R.string.keep_offline)) }
                if (hasCloud && !query.trashOnly && Build.VERSION.SDK_INT >= 29)
                    FilledTonalButton(onClick = { actions.runBatch(BatchAction.SAVE_TO_PHONE) }, modifier = Modifier.testTag("save-selection-to-phone")) {
                        Text(stringResource(R.string.save_to_phone_gallery))
                    }
                if (hasPhone && !query.trashOnly && selectedItems.any { it.origin == MediaOrigin.DEVICE && UploadMediaFormat.isCandidate(it.mimeType) }) FilledTonalButton(enabled = session.accountState == ICloudAccountState.Demo || (session.accountState as? ICloudAccountState.SignedIn)?.status == SessionStatus.VERIFIED,
                    onClick = { actions.runBatch(BatchAction.UPLOAD) }) { Text(stringResource(if (session.accountState == ICloudAccountState.Demo) R.string.demo_upload_title else R.string.upload_phone_jpegs)) }
                Box {
                    OutlinedButton(onClick = { more = true }) { Text(stringResource(R.string.more)) }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        if (!query.trashOnly && !hasShared) {
                            val canFavorite = !session.accountBusy && !mediaBusy && (!hasCloud || editable) && withinLimit
                            DropdownMenuItem(text = { Text(stringResource(R.string.favorite)) }, enabled = canFavorite,
                                onClick = { more = false; actions.setFavorites(selectedItems.map { it.id }, true) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.unfavorite)) }, enabled = canFavorite,
                                onClick = { more = false; actions.setFavorites(selectedItems.map { it.id }, false) })
                        }
                        if (Build.VERSION.SDK_INT >= 30 && hasPhone && !hasCloud) DropdownMenuItem(
                            text = { Text(stringResource(if (query.trashOnly) R.string.restore_photo else R.string.trash_phone_copy)) },
                            onClick = { more = false; actions.trashOnPhone(selectedItems, query.trashOnly) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.add_to_album)) }, enabled = editable && !hasShared && !query.trashOnly && hasCloud && !hasPhone && withinLimit,
                            onClick = { more = false; addingToAlbum = true })
                        val album = gallery.collections.firstOrNull { it.id == query.collectionId && it.isUserAlbum }
                        if (album != null && !query.trashOnly) DropdownMenuItem(text = { Text(stringResource(R.string.remove_from_album)) },
                            enabled = editable && hasCloud && !hasPhone && withinLimit,
                            onClick = { more = false; actions.confirmRemoveFromAlbum(album.id, selectedItems.map { it.id }) })
                        if (hasCloud && !hasPhone && !hasShared) DropdownMenuItem(text = { Text(stringResource(if (query.trashOnly) R.string.restore_from_icloud else R.string.trash_in_icloud)) },
                            enabled = editable && withinLimit,
                            onClick = { more = false; actions.confirmCloudTrash(selectedItems.map { it.id }, !query.trashOnly) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.remove_offline_copies)) }, enabled = hasCloud,
                            onClick = { more = false; actions.runBatch(BatchAction.REMOVE_CACHE) })
                    }
                }
            }
            if (!withinLimit) Text(stringResource(R.string.selection_action_limit), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (addingToAlbum) AlbumChooser(gallery.collections, dismiss = { addingToAlbum = false }, create = { name ->
        actions.createAlbumWithPhotos(name, gallery.selection.toList()); addingToAlbum = false
    }) { id ->
        actions.addToAlbum(id, gallery.selection.toList()); addingToAlbum = false
    }
}

/** The most selected items one share, favorite or album action takes. */
private const val MAX_SELECTION_ACTION_ITEMS = 50

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

/** A personal iCloud album or folder: not shared, not a smart collection and not a phone folder. */
internal val GalleryCollection.isPersonal: Boolean
    get() = shared == null && !id.startsWith("smart:") && !id.startsWith("device-folder:")

/** A personal album that holds photos, as opposed to a folder of albums. */
internal val GalleryCollection.isUserAlbum: Boolean get() = isPersonal && !isFolder

/** Library edits need a usable account, and wait for running media actions and account changes. */
internal fun SessionState.canEditLibrary(mediaBusy: Boolean) = (accountState == ICloudAccountState.Demo ||
    (accountState as? ICloudAccountState.SignedIn)?.status == SessionStatus.VERIFIED) &&
    !mediaBusy && !accountBusy


@Composable
internal fun ICloudIcon(@DrawableRes resource: Int) {
    Icon(painterResource(resource), contentDescription = null, tint = AlbaTheme.colors.accent, modifier = Modifier.size(24.dp))
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
    AlbaBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            AlbaSheetHeading(stringResource(R.string.add_to_album), dismiss = dismiss)
            val albums = collections.filter { it.isUserAlbum }
            TextButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.new_album))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), contentPadding = PaddingValues(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
                items(albums, key = { it.id }) { album ->
                    AlbaListRow(album.name, { choose(album.id) }, subtitle = collections.firstOrNull { it.id == album.parentId }?.name,
                        leading = { ICloudIcon(R.drawable.icloud_albums) }, shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = AlbaSpacing.md, vertical = AlbaSpacing.sm))
                }
            }
        }
    }
}
