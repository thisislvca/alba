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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.gallery.GalleryState
import me.lvca.alba.app.gallery.withBurstFrames
import me.lvca.alba.app.platform.FREE_UP_SPACE_ENABLED
import me.lvca.alba.app.platform.HIDE_EDITS_ENABLED
import me.lvca.alba.app.ui.GalleryScreenActions
import me.lvca.alba.app.ui.collections.AlbumChooser
import me.lvca.alba.app.ui.collections.isUserAlbum
import me.lvca.alba.app.ui.designsystem.AlbaFloatingDefaults
import me.lvca.alba.app.ui.designsystem.AlbaFloatingToolbar
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.AlbaMenu
import me.lvca.alba.app.ui.designsystem.AlbaMenuDivider
import me.lvca.alba.app.ui.designsystem.AlbaMenuItem
import me.lvca.alba.app.ui.designsystem.FavoriteIcon
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.designsystem.theme.floating
import me.lvca.alba.engine.companion.BatchAction
import me.lvca.alba.engine.model.*
import me.lvca.alba.engine.source.UploadMediaFormat
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus

/**
 * The selection's actions in an M3 Expressive floating toolbar, like Photos' floating toolbars: icon buttons, labelled
 * only when every label fits the width, with More opening the floating menu.
 */
@Composable
internal fun SelectionActions(gallery: GalleryState, session: SessionState, mediaBusy: Boolean, actions: GalleryScreenActions,
    modifier: Modifier = Modifier) {
    var addingToAlbum by rememberSaveable { mutableStateOf(false) }
    val query = gallery.query
    if (gallery.selection.isNotEmpty()) Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val selectedItems = gallery.items.filter { it.id in gallery.selection }
        val hasCloud = selectedItems.any { it.origin == MediaOrigin.ICLOUD }
        val hasPhone = selectedItems.any { it.origin == MediaOrigin.DEVICE }
        // A stacked burst counts as all of its photos, which every edit acts on.
        val withinLimit = gallery.withBurstFrames(gallery.selection).size <= MAX_SELECTION_ACTION_ITEMS
        var more by remember { mutableStateOf(false) }
        if (!withinLimit) Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = AlbaFloatingDefaults.ToolbarShadow) {
            Text(stringResource(R.string.selection_action_limit), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall)
        }
        val share = SelectionTool(Icons.Outlined.Share, stringResource(R.string.share), stringResource(R.string.share),
            enabled = !mediaBusy && withinLimit) { actions.share(selectedItems) }
        val offline = SelectionTool(AlbaIcons.DownloadForOffline, stringResource(R.string.selection_offline_short),
            stringResource(R.string.keep_offline)) { actions.runBatch(BatchAction.KEEP_OFFLINE) }
        val save = SelectionTool(AlbaIcons.SaveAlt, stringResource(R.string.selection_save_short),
            stringResource(R.string.save_to_phone_gallery), tag = "save-selection-to-phone") { actions.runBatch(BatchAction.SAVE_TO_PHONE) }
        val upload = SelectionTool(AlbaIcons.CloudUpload, stringResource(R.string.selection_upload_short),
            stringResource(if (session.accountState == ICloudAccountState.Demo) R.string.demo_upload_title else R.string.upload_phone_jpegs),
            enabled = session.accountState == ICloudAccountState.Demo ||
                (session.accountState as? ICloudAccountState.SignedIn)?.status == SessionStatus.VERIFIED) { actions.runBatch(BatchAction.UPLOAD) }
        val moreTool = SelectionTool(Icons.Outlined.MoreVert, stringResource(R.string.more), stringResource(R.string.more),
            tag = "selection-more") { more = true }
        val uploadable = hasPhone && !query.trashOnly &&
            selectedItems.any { it.origin == MediaOrigin.DEVICE && UploadMediaFormat.isCandidate(it.mimeType) }
        val tools = listOfNotNull(share, offline.takeIf { hasCloud }, save.takeIf { hasCloud && !query.trashOnly },
            upload.takeIf { uploadable }, moreTool)
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val labelled = toolbarFitsLabels(tools.map { it.label }, maxWidth)
            AlbaFloatingToolbar(Modifier.testTag("selection-toolbar")) {
                tools.forEach { tool ->
                    if (tool === moreTool) Box {
                        SelectionAction(tool, labelled)
                        AlbaMenu(expanded = more, onDismissRequest = { more = false }, modifier = Modifier.testTag("selection-more-menu")) {
                            SelectionMenu(SelectionFacts(gallery, session, mediaBusy, selectedItems, withinLimit), actions,
                                close = { more = false }, addToAlbum = { addingToAlbum = true })
                        }
                    } else SelectionAction(tool, labelled)
                }
            }
        }
    }
    if (addingToAlbum) AlbumChooser(gallery.collections, dismiss = { addingToAlbum = false }, create = { name ->
        actions.createAlbumWithPhotos(name, gallery.selection.toList()); addingToAlbum = false
    }) { id ->
        actions.addToAlbum(id, gallery.selection.toList()); addingToAlbum = false
    }
}

/** What the selection holds and where it is, which decides the menu's items. */
private class SelectionFacts(gallery: GalleryState, session: SessionState, mediaBusy: Boolean, val items: List<GalleryMedia>,
    val withinLimit: Boolean) {
    val query = gallery.query
    val ids = items.map { it.id }
    val editable = session.canEditLibrary(mediaBusy)
    val hasCloud = items.any { it.origin == MediaOrigin.ICLOUD }
    val hasShared = items.any { it.isShared }
    val hasPhone = items.any { it.origin == MediaOrigin.DEVICE }
    val trashOnly = query.trashOnly

    /** iCloud refuses favorites, albums and the trash for hidden photos; Hidden offers only what works there. */
    val inHidden = query.collectionId == GalleryQuery.HIDDEN
    val canFavorite = !session.accountBusy && !mediaBusy && (!hasCloud || editable) && withinLimit
    val favorites = !trashOnly && !hasShared && !inHidden
    val cloudOnly = hasCloud && !hasPhone
    val hideable = cloudOnly && !hasShared
    val hiding = HIDE_EDITS_ENABLED && hideable && !trashOnly
    val album = gallery.collections.firstOrNull { it.id == query.collectionId && it.isUserAlbum }?.takeIf { !trashOnly }
    val connected = (session.accountState as? ICloudAccountState.SignedIn)?.status == SessionStatus.VERIFIED

    /** Phone items only and backed up: Free up space re-checks each against iCloud before Android trashes it. */
    val freeUpSpace = FREE_UP_SPACE_ENABLED && hasPhone && !hasCloud && !trashOnly && connected

    /** Whether the album group shows any item. */
    val albumGroup = !inHidden || album != null || hiding

    /** Phone and iCloud items together: one confirmation, then each goes to its own trash. */
    val mixed = hasCloud && hasPhone && !hasShared && !trashOnly
}

/** More's items, grouped as Photos groups its menus: favourites, then albums and hiding, then the trash and offline copies. */
@Composable
private fun SelectionMenu(facts: SelectionFacts, actions: GalleryScreenActions, close: () -> Unit, addToAlbum: () -> Unit) {
    if (facts.favorites) {
        AlbaMenuItem(stringResource(R.string.favorite), enabled = facts.canFavorite, leadingIcon = { FavoriteIcon(false, null) },
            onClick = { close(); actions.setFavorites(facts.ids, true) })
        AlbaMenuItem(stringResource(R.string.unfavorite), enabled = facts.canFavorite,
            leadingIcon = { FavoriteIcon(true, null, tint = LocalContentColor.current) },
            onClick = { close(); actions.setFavorites(facts.ids, false) })
        AlbaMenuDivider()
    }
    AlbumItems(facts, actions, close, addToAlbum)
    if (facts.albumGroup) AlbaMenuDivider()
    TrashItems(facts, actions, close)
}

/** Adding to and removing from albums, and hiding. */
@Composable
private fun AlbumItems(facts: SelectionFacts, actions: GalleryScreenActions, close: () -> Unit, addToAlbum: () -> Unit) {
    val allowed = facts.editable && facts.withinLimit
    if (!facts.inHidden) AlbaMenuItem(stringResource(R.string.add_to_album),
        leadingIcon = { Icon(painterResource(R.drawable.icloud_albums), null) },
        enabled = allowed && facts.cloudOnly && !facts.hasShared && !facts.trashOnly,
        onClick = { close(); addToAlbum() })
    facts.album?.let { album ->
        AlbaMenuItem(stringResource(R.string.remove_from_album), enabled = allowed && facts.cloudOnly,
            leadingIcon = { Icon(Icons.Outlined.Close, null) },
            onClick = { close(); actions.confirmRemoveFromAlbum(album.id, facts.ids) })
    }
    if (facts.hiding) AlbaMenuItem(stringResource(if (facts.inHidden) R.string.unhide else R.string.hide),
        enabled = allowed, modifier = Modifier.testTag("selection-hide"),
        leadingIcon = { Icon(if (facts.inHidden) AlbaIcons.Visibility else AlbaIcons.VisibilityOff, null) },
        onClick = { close(); actions.setHidden(facts.ids, !facts.inHidden) })
}

/** The phone and iCloud trash, freeing space, and offline copies. */
@Composable
private fun TrashItems(facts: SelectionFacts, actions: GalleryScreenActions, close: () -> Unit) {
    val allowed = facts.editable && facts.withinLimit
    val phoneOnly = facts.hasPhone && !facts.hasCloud
    if (phoneOnly) AlbaMenuItem(stringResource(if (facts.trashOnly) R.string.restore_photo else R.string.trash_phone_copy),
        leadingIcon = { Icon(AlbaIcons.DeleteOutline, null) },
        onClick = { close(); actions.trashOnPhone(facts.items, facts.trashOnly) })
    if (facts.freeUpSpace) AlbaMenuItem(stringResource(R.string.free_up_space_title),
        leadingIcon = { Icon(AlbaIcons.DeleteSweep, null) }, modifier = Modifier.testTag("selection-free-up-space"),
        onClick = { close(); actions.freeUpSpace(facts.ids) })
    if (facts.hideable && !facts.inHidden) AlbaMenuItem(
        stringResource(if (facts.trashOnly) R.string.restore_from_icloud else R.string.trash_in_icloud),
        enabled = allowed, leadingIcon = { Icon(AlbaIcons.DeleteOutline, null) },
        onClick = { close(); actions.confirmCloudTrash(facts.ids, !facts.trashOnly) })
    if (facts.mixed) AlbaMenuItem(stringResource(R.string.mixed_trash_action),
        enabled = allowed, leadingIcon = { Icon(AlbaIcons.DeleteOutline, null) },
        modifier = Modifier.testTag("trash-mixed-selection"),
        onClick = { close(); actions.confirmMixedTrash(facts.items) })
    AlbaMenuItem(stringResource(R.string.remove_offline_copies), enabled = facts.hasCloud, leadingIcon = { Icon(AlbaIcons.CloudOff, null) },
        onClick = { close(); actions.runBatch(BatchAction.REMOVE_CACHE) })
}

/**
 * Google Photos' contextual bar: close and "N selected" take the header's place while choosing photos, sliding over
 * it so the grid below never moves. It covers at least [minHeight], the header's visible height, in the selection
 * toolbar's surface colour.
 */
@Composable
internal fun SelectionTopBar(count: Int, minHeight: Dp, reducedMotion: Boolean, selectAll: () -> Unit, clear: () -> Unit,
    modifier: Modifier = Modifier) {
    // Keep the last count while the bar slides away, rather than showing "0 selected".
    var shown by remember { mutableIntStateOf(count) }
    if (count > 0) shown = count
    val slide = if (reducedMotion) snap<IntOffset>() else tween(GalleryFeel.CONTEXT_BAR_SLIDE_MILLIS)
    AnimatedVisibility(count > 0, modifier, enter = slideInVertically(slide) { -it }, exit = slideOutVertically(slide) { -it }) {
        Surface(Modifier.fillMaxWidth().heightIn(min = minHeight).testTag("selection-top-bar"), color = AlbaTheme.floating.toolbar) {
            Row(Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(start = 4.dp, end = 8.dp)
                .heightIn(min = AlbaFloatingDefaults.ToolbarHeight), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = clear) { Icon(Icons.Outlined.Close, stringResource(R.string.cancel_selection)) }
                Text(pluralStringResource(R.plurals.selected_count, shown, shown), style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = selectAll) { Text(stringResource(R.string.select_all_shown)) }
            }
        }
    }
}

/** One toolbar action: an icon over its short [label] when labels fit, and [description] for accessibility. */
private class SelectionTool(val icon: ImageVector, val label: String, val description: String, val enabled: Boolean = true,
    val tag: String? = null, val onClick: () -> Unit)

/** Whether every label fits under its icon in a toolbar no wider than [available]. */
@Composable
private fun toolbarFitsLabels(labels: List<String>, available: Dp): Boolean {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current
    val items = labels.fold(0.dp) { sum, label ->
        val text = with(density) { measurer.measure(label, style, maxLines = 1).size.width.toDp() }
        sum + maxOf(text + LabelledToolPadding * 2, LabelledToolMinWidth)
    }
    val chrome = AlbaFloatingDefaults.ToolbarPadding * 2 + AlbaFloatingDefaults.ToolbarItemSpacing * (labels.size - 1)
    return items + chrome <= available
}

@Composable
private fun SelectionAction(tool: SelectionTool, labelled: Boolean) {
    val color = if (tool.enabled) LocalContentColor.current else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
    val tagged = if (tool.tag != null) Modifier.testTag(tool.tag) else Modifier
    if (!labelled) IconButton(onClick = tool.onClick, enabled = tool.enabled, modifier = tagged) {
        Icon(tool.icon, tool.description, tint = color)
    } else Column(tagged.widthIn(min = LabelledToolMinWidth).height(LabelledToolHeight).clip(MaterialTheme.shapes.extraLarge)
        .clickable(enabled = tool.enabled, role = Role.Button, onClick = tool.onClick)
        .semantics { contentDescription = tool.description }
        .padding(horizontal = LabelledToolPadding),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        Icon(tool.icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Text(tool.label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val LabelledToolPadding = 8.dp
private val LabelledToolMinWidth = 56.dp
private val LabelledToolHeight = 56.dp

/** The most selected items one share, favorite or album action takes. */
private const val MAX_SELECTION_ACTION_ITEMS = 50

/** Library edits need a usable account, and wait for running media actions and account changes. */
internal fun SessionState.canEditLibrary(mediaBusy: Boolean) = (accountState == ICloudAccountState.Demo ||
    (accountState as? ICloudAccountState.SignedIn)?.status == SessionStatus.VERIFIED) &&
    !mediaBusy && !accountBusy
