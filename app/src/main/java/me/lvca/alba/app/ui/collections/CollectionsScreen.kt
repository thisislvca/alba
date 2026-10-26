package me.lvca.alba.app.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.gallery.GalleryState
import me.lvca.alba.app.ui.designsystem.AlbaCard
import me.lvca.alba.app.ui.designsystem.AlbaChevron
import me.lvca.alba.app.ui.designsystem.AlbaDivider
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.AlbaListRow
import me.lvca.alba.app.ui.designsystem.ICloudIcon
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing
import me.lvca.alba.app.ui.designsystem.theme.mosaicCell
import me.lvca.alba.app.ui.localizedTitle
import me.lvca.alba.app.ui.shared.LocalSharedActivity
import me.lvca.alba.engine.model.*

internal enum class CollectionsPage(val title: Int) {
    ALBUMS(R.string.albums),
    SHARED(R.string.shared_albums),
    DEVICE(R.string.on_this_phone)
}

@Composable
internal fun CollectionsScreen(gallery: GalleryState, canEdit: Boolean, previewRetryVersion: Int, folder: String?, page: CollectionsPage?,
    summaries: Map<String, CollectionPreview>, scroll: LazyGridState, preview: (String) -> Unit,
    openPage: (CollectionsPage) -> Unit, openFolder: (String) -> Unit, openCollection: (String) -> Unit, openOffline: () -> Unit,
    openSharedInvitations: () -> Unit = {}, createSharedAlbum: () -> Unit = {}, openTrash: () -> Unit = {}, openCloudTrash: () -> Unit = {},
    createAlbum: () -> Unit, requestPhotos: () -> Unit, bottomSpace: Dp = 16.dp) {
    var sort by rememberSaveable { mutableStateOf(AlbumSort.RECENT) }
    val sortable = page != CollectionsPage.DEVICE
    val albums = remember(gallery.collections, folder, page, sort, summaries) {
        pageCollections(gallery.collections, folder, page, sort.takeIf { sortable }, summaries)
    }
    val activity = LocalSharedActivity.current
    LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), state = scroll, modifier = Modifier.fillMaxSize().testTag("collections-grid"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomSpace), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(AlbaSpacing.xl)) {
        if (page == null && folder == null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                val shortcutGap = 10.dp
                Column(verticalArrangement = Arrangement.spacedBy(shortcutGap)) {
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(shortcutGap)) {
                        ShortcutCard(stringResource(R.string.favorites), Modifier.weight(1f), { ICloudIcon(R.drawable.icloud_favorites) }) { openCollection(GalleryQuery.FAVORITES) }
                        ShortcutCard(stringResource(R.string.offline), Modifier.weight(1f), { Icon(AlbaIcons.DownloadForOffline, null) }, openOffline)
                    }
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(shortcutGap)) {
                        ShortcutCard(stringResource(R.string.phone_trash), Modifier.weight(1f), { Icon(AlbaIcons.DeleteOutline, null) }, openTrash)
                        ShortcutCard(stringResource(R.string.icloud_recently_deleted), Modifier.weight(1f), { Icon(AlbaIcons.CloudQueue, null) }, openCloudTrash)
                    }
                }
            }
            item {
                MosaicCard(stringResource(R.string.albums), summaries["hub:albums"]?.media.orEmpty(), previewRetryVersion, preview, "collection-albums", painterResource(R.drawable.icloud_albums)) { openPage(CollectionsPage.ALBUMS) }
            }
            item {
                val covers = summaries["hub:shared"]?.media.orEmpty()
                val icon = painterResource(R.drawable.icloud_shared_albums)
                MosaicCard(stringResource(R.string.shared_albums), covers, previewRetryVersion, preview, "collection-shared", icon) {
                    openPage(CollectionsPage.SHARED)
                }
            }
            item {
                MosaicCard(stringResource(R.string.on_this_phone), summaries["hub:device"]?.media.orEmpty(), previewRetryVersion, preview, "collection-device", rememberVectorPainter(AlbaIcons.PhoneAndroid)) { openPage(CollectionsPage.DEVICE) }
            }
            item {
                MosaicCard(stringResource(R.string.videos), summaries[SmartCollection.VIDEOS.id]?.media.orEmpty(), previewRetryVersion, preview, "collection-videos", painterResource(R.drawable.icloud_videos)) { openCollection(SmartCollection.VIDEOS.id) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(top = AlbaSpacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ICloudIcon(R.drawable.icloud_media_types)
                    Text(stringResource(R.string.media_types), style = MaterialTheme.typography.titleLarge)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                AlbaCard {
                    SmartCollection.entries.filter { it != SmartCollection.VIDEOS }.forEachIndexed { index, smart ->
                        if (index > 0) AlbaDivider(Modifier.padding(start = 60.dp))
                        CollectionRow(smart.localizedTitle(), null, summaries[smart.id]?.count ?: 0, { ICloudIcon(smart.icon()) }) { openCollection(smart.id) }
                    }
                }
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(pluralStringResource(R.plurals.album_count, albums.size, albums.size),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (sortable && albums.size > 1) AlbumSortMenu(sort) { sort = it }
                    if (page == CollectionsPage.SHARED) {
                        InvitationsButton(activity.unreadInvitations, canEdit, openSharedInvitations)
                        IconButton(onClick = createSharedAlbum, enabled = canEdit, modifier = Modifier.testTag("create-shared-album")) { Icon(Icons.Outlined.Add, stringResource(R.string.shared_create)) }
                    } else if (page != CollectionsPage.DEVICE) {
                        TextButton(onClick = createAlbum, enabled = canEdit) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(AlbaSpacing.xs)); Text(stringResource(R.string.new_album)) }
                    }
                }
            }
            if (albums.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(when (page) {
                    CollectionsPage.SHARED -> R.string.shared_empty
                    CollectionsPage.DEVICE -> R.string.choose_photos_to_include
                    else -> R.string.your_personal_albums_will_appear_here
                }), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = AlbaSpacing.xxl))
            }
            items(albums, key = { it.id }) { album ->
                val summary = if (album.isFolder) {
                    val children = gallery.collections.filter { it.parentId == album.id }
                    CollectionPreview(children.size, children.flatMap { summaries[it.id]?.media.orEmpty() }.take(6))
                } else summaries[album.id]
                AlbumCard(album, summary, previewRetryVersion, preview, unread = album.id in activity.unreadAlbums) {
                    if (album.isFolder) openFolder(album.id) else openCollection(album.id)
                }
            }
        }
        if (page == null || page == CollectionsPage.DEVICE) item(span = { GridItemSpan(maxLineSpan) }) {
            CollectionRow(stringResource(R.string.this_phone), if (gallery.limitedPhotoAccess) stringResource(R.string.change_selected_phone_photos) else if (gallery.deviceMediaAccess) stringResource(R.string.manage_photo_access) else stringResource(R.string.choose_photos_to_include),
                icon = { Icon(AlbaIcons.PhoneAndroid, null) }, onClick = requestPhotos)
        }
    }
}

/** The albums [page] lists, in [sort] order when the page is sortable; recency is the newest photo in an album or folder. */
internal fun pageCollections(collections: List<GalleryCollection>, folder: String?, page: CollectionsPage?, sort: AlbumSort?,
    summaries: Map<String, CollectionPreview>): List<GalleryCollection> {
    val shown = collections.filter {
        when (page) {
            CollectionsPage.SHARED -> it.shared != null
            CollectionsPage.DEVICE -> it.id.startsWith("device-folder:")
            else -> it.parentId == folder && it.isPersonal
        }
    }
    return sort?.apply(shown) { album ->
        val members = if (album.isFolder) collections.filter { it.parentId == album.id }.map { it.id } else listOf(album.id)
        members.mapNotNull { summaries[it]?.media?.maxOfOrNull(GalleryMedia::capturedAtEpochMillis) }.maxOrNull()
    } ?: shown
}

/** The invitations inbox, with a count of invitations not looked at yet. */
@Composable
private fun InvitationsButton(unread: Int, enabled: Boolean, open: () -> Unit) {
    IconButton(onClick = open, enabled = enabled, modifier = Modifier.testTag("shared-invitations-button")) {
        BadgedBox(badge = { if (unread > 0) Badge(Modifier.testTag("shared-invitations-badge")) { Text(unread.toString()) } }) {
            Icon(Icons.Outlined.Email, stringResource(R.string.shared_invitations))
        }
    }
}

@Composable
private fun AlbumSortMenu(sort: AlbumSort, change: (AlbumSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("album-sort")) {
            Icon(AlbaIcons.SwapVert, null, Modifier.size(18.dp)); Spacer(Modifier.width(AlbaSpacing.xs)); Text(stringResource(sort.label))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            AlbumSort.entries.forEach { option ->
                DropdownMenuItem(text = { Text(stringResource(option.label)) }, onClick = { open = false; change(option) },
                    trailingIcon = if (option == sort) ({ Icon(Icons.Outlined.Check, null) }) else null,
                    modifier = Modifier.testTag("album-sort-${option.name}"))
            }
        }
    }
}

@Composable
private fun MosaicCard(title: String, covers: List<GalleryMedia>, previewRetryVersion: Int, preview: (String) -> Unit, tag: String,
    emptyIcon: Painter, click: () -> Unit) {
    // Clip the press ripple to the card corners rather than a hard rectangle over cover and title.
    Column(Modifier.clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClick = click).testTag(tag)) {
        AlbaCard(shape = MaterialTheme.shapes.medium) {
            if (covers.isEmpty()) Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                Icon(emptyIcon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else if (covers.size == 1) CoverImage(covers.first(), previewRetryVersion, preview, Modifier.fillMaxWidth().aspectRatio(1f))
            else Column(Modifier.fillMaxWidth().aspectRatio(1f).padding(AlbaSpacing.xs).clip(MaterialTheme.shapes.small), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(2) { row ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(2) { col ->
                            val cell = Modifier.weight(1f).fillMaxHeight().clip(MaterialTheme.shapes.mosaicCell)
                            val cover = covers.getOrNull(row * 2 + col)
                            if (cover == null) Box(cell.background(MaterialTheme.colorScheme.surfaceContainerHigh))
                            else CoverImage(cover, previewRetryVersion, preview, cell)
                        }
                    }
                }
            }
        }
        Text(title, Modifier.padding(top = 10.dp, bottom = AlbaSpacing.xs), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ShortcutCard(title: String, modifier: Modifier, icon: @Composable () -> Unit, click: () -> Unit) {
    AlbaCard(modifier.fillMaxHeight(), onClick = click, shape = MaterialTheme.shapes.medium, verticalArrangement = Arrangement.Center) {
        Row(Modifier.heightIn(min = 58.dp).padding(horizontal = 14.dp, vertical = AlbaSpacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AlbaSpacing.md)) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
internal fun CollectionRow(title: String, subtitle: String?, count: Int? = null, icon: @Composable () -> Unit, onClick: () -> Unit) {
    AlbaListRow(title, onClick, subtitle = subtitle, leading = icon, shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = AlbaSpacing.xs, vertical = AlbaSpacing.sm),
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                count?.let { Text(it.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                AlbaChevron()
            }
        })
}
