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
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import me.lvca.alba.app.R
import me.lvca.alba.app.GalleryState
import me.lvca.alba.app.SessionState
import me.lvca.alba.engine.companion.BatchAction
import me.lvca.alba.engine.model.*
import me.lvca.alba.protocol.account.ICloudAccountState
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material.icons.Icons
import android.os.Build
import me.lvca.alba.engine.source.UploadMediaFormat
import me.lvca.alba.protocol.account.SessionStatus

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

/** Library edits need a usable account, and wait for running media actions and account changes. */
internal fun SessionState.canEditLibrary(mediaBusy: Boolean) = (accountState == ICloudAccountState.Demo ||
    (accountState as? ICloudAccountState.SignedIn)?.status == SessionStatus.VERIFIED) &&
    !mediaBusy && !accountBusy
