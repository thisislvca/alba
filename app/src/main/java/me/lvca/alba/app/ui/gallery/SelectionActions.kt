@file:OptIn(ExperimentalLayoutApi::class)

package me.lvca.alba.app.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.gallery.GalleryState
import me.lvca.alba.app.ui.GalleryScreenActions
import me.lvca.alba.app.ui.collections.AlbumChooser
import me.lvca.alba.app.ui.collections.isUserAlbum
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.theme.galleryOverlay
import me.lvca.alba.engine.companion.BatchAction
import me.lvca.alba.engine.model.*
import me.lvca.alba.engine.source.UploadMediaFormat
import me.lvca.alba.protocol.account.ICloudAccountState
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
            // Icons with short labels share the width evenly, so every action fits; the full name is for accessibility.
            Row(Modifier.fillMaxWidth()) {
                SelectionAction(Icons.Outlined.Share, stringResource(R.string.share), stringResource(R.string.share),
                    enabled = !mediaBusy && withinLimit, modifier = Modifier.weight(1f)) { actions.share(selectedItems) }
                if (hasCloud) SelectionAction(AlbaIcons.DownloadForOffline, stringResource(R.string.selection_offline_short),
                    stringResource(R.string.keep_offline), modifier = Modifier.weight(1f)) { actions.runBatch(BatchAction.KEEP_OFFLINE) }
                if (hasCloud && !query.trashOnly)
                    SelectionAction(AlbaIcons.SaveAlt, stringResource(R.string.selection_save_short), stringResource(R.string.save_to_phone_gallery),
                        modifier = Modifier.weight(1f).testTag("save-selection-to-phone")) { actions.runBatch(BatchAction.SAVE_TO_PHONE) }
                if (hasPhone && !query.trashOnly && selectedItems.any { it.origin == MediaOrigin.DEVICE && UploadMediaFormat.isCandidate(it.mimeType) })
                    SelectionAction(AlbaIcons.CloudUpload, stringResource(R.string.selection_upload_short),
                        stringResource(if (session.accountState == ICloudAccountState.Demo) R.string.demo_upload_title else R.string.upload_phone_jpegs),
                        enabled = session.accountState == ICloudAccountState.Demo ||
                            (session.accountState as? ICloudAccountState.SignedIn)?.status == SessionStatus.VERIFIED,
                        modifier = Modifier.weight(1f)) { actions.runBatch(BatchAction.UPLOAD) }
                Box(Modifier.weight(1f)) {
                    SelectionAction(Icons.Outlined.MoreVert, stringResource(R.string.more), stringResource(R.string.more),
                        modifier = Modifier.fillMaxWidth()) { more = true }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        if (!query.trashOnly && !hasShared) {
                            val canFavorite = !session.accountBusy && !mediaBusy && (!hasCloud || editable) && withinLimit
                            DropdownMenuItem(text = { Text(stringResource(R.string.favorite)) }, enabled = canFavorite,
                                onClick = { more = false; actions.setFavorites(selectedItems.map { it.id }, true) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.unfavorite)) }, enabled = canFavorite,
                                onClick = { more = false; actions.setFavorites(selectedItems.map { it.id }, false) })
                        }
                        if (hasPhone && !hasCloud) DropdownMenuItem(
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

/**
 * Google Photos' contextual bar: close and "N selected" take the header's place while choosing photos, sliding over
 * it so the grid below never moves. It covers at least [minHeight], the header's visible height.
 */
@Composable
internal fun SelectionTopBar(count: Int, minHeight: Dp, reducedMotion: Boolean, selectAll: () -> Unit, clear: () -> Unit,
    modifier: Modifier = Modifier) {
    // Keep the last count while the bar slides away, rather than showing "0 selected".
    var shown by remember { mutableIntStateOf(count) }
    if (count > 0) shown = count
    val slide = if (reducedMotion) snap<IntOffset>() else tween(GalleryFeel.CONTEXT_BAR_SLIDE_MILLIS)
    AnimatedVisibility(count > 0, modifier, enter = slideInVertically(slide) { -it }, exit = slideOutVertically(slide) { -it }) {
        Surface(Modifier.fillMaxWidth().heightIn(min = minHeight).testTag("selection-top-bar"), color = MaterialTheme.colorScheme.galleryOverlay) {
            Row(Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(start = 4.dp, end = 8.dp)
                .heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = clear) { Icon(Icons.Outlined.Close, stringResource(R.string.cancel_selection)) }
                Text(pluralStringResource(R.plurals.selected_count, shown, shown), style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
                TextButton(onClick = selectAll) { Text(stringResource(R.string.select_all_shown)) }
            }
        }
    }
}

/** One action for the selection: an icon over a short [label]. [description] names it in full for accessibility. */
@Composable
private fun SelectionAction(
    icon: ImageVector,
    label: String,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
    Column(modifier.heightIn(min = 56.dp).clip(MaterialTheme.shapes.small)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }
        .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The most selected items one share, favorite or album action takes. */
private const val MAX_SELECTION_ACTION_ITEMS = 50

/** Library edits need a usable account, and wait for running media actions and account changes. */
internal fun SessionState.canEditLibrary(mediaBusy: Boolean) = (accountState == ICloudAccountState.Demo ||
    (accountState as? ICloudAccountState.SignedIn)?.status == SessionStatus.VERIFIED) &&
    !mediaBusy && !accountBusy
