package dev.mela.app.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import dev.mela.app.GalleryUiState
import dev.mela.app.R
import dev.mela.engine.model.*

internal enum class CollectionsPage(val title: Int) {
    ALBUMS(R.string.albums), SHARED(R.string.shared_albums), DEVICE(R.string.on_this_phone)
}

@Composable
internal fun CollectionsScreen(state: GalleryUiState, folder: String?, page: CollectionsPage?,
    summaries: Map<String, CollectionPreview>, scroll: LazyGridState, preview: (String) -> Unit,
    openPage: (CollectionsPage) -> Unit, openFolder: (String) -> Unit, openCollection: (String) -> Unit, openOffline: () -> Unit,
    openSharedInvitations: () -> Unit = {}, createSharedAlbum: () -> Unit = {}, openTrash: () -> Unit = {}, openCloudTrash: () -> Unit = {},
    createAlbum: () -> Unit, requestPhotos: () -> Unit, bottomSpace: androidx.compose.ui.unit.Dp = 16.dp) {
    val albums = remember(state.collections, folder, page) { state.collections.filter {
        when (page) {
            CollectionsPage.SHARED -> it.shared != null
            CollectionsPage.DEVICE -> it.id.startsWith("device-folder:")
            else -> it.parentId == folder && it.shared == null && !it.id.startsWith("smart:") && !it.id.startsWith("device-folder:")
        }
    } }
    LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), state = scroll, modifier = Modifier.fillMaxSize().testTag("collections-grid"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomSpace), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (page == null && folder == null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                val shortcutGap = 10.dp
                Column(verticalArrangement = Arrangement.spacedBy(shortcutGap)) {
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(shortcutGap)) {
                        ShortcutCard(stringResource(R.string.favorites), Modifier.weight(1f), { ICloudIcon(R.drawable.icloud_favorites) }) { openCollection(GalleryQuery.FAVORITES) }
                        ShortcutCard(stringResource(R.string.offline), Modifier.weight(1f), { Icon(MelaIcons.DownloadForOffline, null) }, openOffline)
                    }
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(shortcutGap)) {
                        ShortcutCard(stringResource(R.string.phone_trash), Modifier.weight(1f), { Icon(MelaIcons.DeleteOutline, null) }, openTrash)
                        ShortcutCard(stringResource(R.string.icloud_recently_deleted), Modifier.weight(1f), { Icon(MelaIcons.CloudQueue, null) }, openCloudTrash)
                    }
                }
            }
            item {
                MosaicCard(stringResource(R.string.albums), summaries["hub:albums"]?.media.orEmpty(), state, preview, "collection-albums") { openPage(CollectionsPage.ALBUMS) }
            }
            item {
                MosaicCard(stringResource(R.string.shared_albums), summaries["hub:shared"]?.media.orEmpty(), state, preview, "collection-shared") { openPage(CollectionsPage.SHARED) }
            }
            item {
                MosaicCard(stringResource(R.string.on_this_phone), summaries["hub:device"]?.media.orEmpty(), state, preview, "collection-device") { openPage(CollectionsPage.DEVICE) }
            }
            item {
                MosaicCard(stringResource(R.string.videos), summaries[SmartCollection.VIDEOS.id]?.media.orEmpty(), state, preview, "collection-videos") { openCollection(SmartCollection.VIDEOS.id) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ICloudIcon(R.drawable.icloud_media_types)
                    Text(stringResource(R.string.media_types), style = MaterialTheme.typography.titleLarge)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                MelaGroup {
                    SmartCollection.entries.filter { it != SmartCollection.VIDEOS }.forEachIndexed { index, smart ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                        CollectionRow(smart.localizedTitle(), null, summaries[smart.id]?.count ?: 0, { ICloudIcon(smart.icon()) }) { openCollection(smart.id) }
                    }
                }
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(androidx.compose.ui.res.pluralStringResource(R.plurals.album_count, albums.size, albums.size),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (page == CollectionsPage.SHARED) {
                        IconButton(onClick = openSharedInvitations, enabled = state.canEditLibrary()) { Icon(Icons.Outlined.Email, stringResource(R.string.shared_invitations)) }
                        IconButton(onClick = createSharedAlbum, enabled = state.canEditLibrary(), modifier = Modifier.testTag("create-shared-album")) { Icon(Icons.Outlined.Add, stringResource(R.string.shared_create)) }
                    } else if (page != CollectionsPage.DEVICE) {
                        TextButton(onClick = createAlbum, enabled = state.canEditLibrary()) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.new_album)) }
                    }
                }
            }
            if (albums.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(when (page) {
                    CollectionsPage.SHARED -> R.string.shared_empty
                    CollectionsPage.DEVICE -> R.string.choose_photos_to_include
                    else -> R.string.your_personal_albums_will_appear_here
                }), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
            }
            items(albums, key = { it.id }) { album ->
                val summary = if (album.isFolder) {
                    val children = state.collections.filter { it.parentId == album.id }
                    CollectionPreview(children.size, children.flatMap { summaries[it.id]?.media.orEmpty() }.take(6))
                } else summaries[album.id]
                AlbumCard(album, summary, state.previewRetryVersion, preview) { if (album.isFolder) openFolder(album.id) else openCollection(album.id) }
            }
        }
        if (page == null || page == CollectionsPage.DEVICE) item(span = { GridItemSpan(maxLineSpan) }) {
            CollectionRow(stringResource(R.string.this_phone), if (state.limitedPhotoAccess) stringResource(R.string.change_selected_phone_photos) else if (state.deviceMediaAccess) stringResource(R.string.manage_photo_access) else stringResource(R.string.choose_photos_to_include),
                icon = { Icon(MelaIcons.PhoneAndroid, null) }, onClick = requestPhotos)
        }
    }
}

@Composable
private fun MosaicCard(title: String, covers: List<GalleryMedia>, state: GalleryUiState, preview: (String) -> Unit, tag: String, click: () -> Unit) {
    Column(Modifier.clickable(onClick = click).testTag(tag)) {
        Surface(shape = RoundedCornerShape(16.dp), color = melaGroupColor()) {
            if (covers.isEmpty()) Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                if (tag == "collection-device") Icon(MelaIcons.PhoneAndroid, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                else Icon(androidx.compose.ui.res.painterResource(if (tag == "collection-videos") R.drawable.icloud_videos else R.drawable.icloud_albums), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else if (covers.size == 1) CoverImage(covers.first(), state.previewRetryVersion, preview, Modifier.fillMaxWidth().aspectRatio(1f))
            else Column(Modifier.fillMaxWidth().aspectRatio(1f).padding(4.dp).clip(RoundedCornerShape(12.dp)), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(2) { row ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(2) { col ->
                            val cell = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                            val cover = covers.getOrNull(row * 2 + col)
                            if (cover == null) Box(cell.background(MaterialTheme.colorScheme.surfaceContainerHigh))
                            else CoverImage(cover, state.previewRetryVersion, preview, cell)
                        }
                    }
                }
            }
        }
        Text(title, Modifier.padding(top = 10.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ShortcutCard(title: String, modifier: Modifier, icon: @Composable () -> Unit, click: () -> Unit) {
    Surface(onClick = click, modifier = modifier.fillMaxHeight(), shape = RoundedCornerShape(16.dp), color = melaGroupColor()) {
        Row(Modifier.heightIn(min = 58.dp).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
internal fun CollectionRow(title: String, subtitle: String?, count: Int? = null, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { icon() }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        count?.let { Text(it.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
