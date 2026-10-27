package me.lvca.alba.app.ui.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.designsystem.AlbaBottomSheet
import me.lvca.alba.app.ui.designsystem.AlbaCard
import me.lvca.alba.app.ui.designsystem.AlbaSheetBody
import me.lvca.alba.app.ui.designsystem.AlbaSheetHeading
import me.lvca.alba.app.ui.designsystem.MediaPill
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing
import me.lvca.alba.app.ui.gallery.MediaThumbnail
import me.lvca.alba.app.ui.gallery.mediaDuration
import me.lvca.alba.engine.model.*

@Composable
internal fun SharedAlbumBadge(info: SharedAlbumInfo, click: () -> Unit) {
    AssistChip(onClick = click, label = { Text(stringResource(if (info.generation == SharedAlbumGeneration.MODERN) R.string.shared_new else R.string.shared_legacy)) },
        leadingIcon = { Icon(Icons.Outlined.Info, null, Modifier.size(16.dp)) }, modifier = Modifier.testTag("shared-version-badge"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedAlbumDetails(album: GalleryCollection, dismiss: () -> Unit) {
    val info = requireNotNull(album.shared)
    val uriHandler = LocalUriHandler.current
    AlbaBottomSheet(onDismissRequest = dismiss, modifier = Modifier.testTag("shared-album-details")) {
        AlbaSheetBody(album.name, dismiss, spacing = AlbaSpacing.lg) {
            Text(stringResource(if (info.generation == SharedAlbumGeneration.MODERN) R.string.shared_new_title else R.string.shared_legacy_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(if (info.generation == SharedAlbumGeneration.MODERN) R.string.shared_new_body else R.string.shared_legacy_body), style = MaterialTheme.typography.bodyLarge)
            AlbaCard(contentPadding = PaddingValues(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(when(info.role) {
                        SharedAlbumRole.MANAGER -> R.string.shared_role_manager
                        SharedAlbumRole.COMMENTER -> R.string.shared_role_commenter
                        SharedAlbumRole.OWNER -> R.string.shared_role_owner
                        SharedAlbumRole.CONTRIBUTOR -> R.string.shared_role_contributor
                        SharedAlbumRole.VIEWER -> R.string.shared_role_viewer
                    }), style = MaterialTheme.typography.titleMedium)
                    info.ownerName?.let { Text(stringResource(R.string.shared_owner, it)) }
                    info.participantCount?.let { Text(pluralStringResource(R.plurals.shared_participants, it, it)) }
                    Text(stringResource(R.string.shared_copy_notice), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(stringResource(R.string.shared_upgrade_notice), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            info.webUrl?.let { url ->
                Text(stringResource(R.string.shared_manage_explanation), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { uriHandler.openUri(url) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.shared_manage_icloud)) }
            }
            Button(onClick = dismiss, modifier = Modifier.fillMaxWidth().testTag("shared-understood")) { Text(stringResource(R.string.shared_understood)) }
        }
    }
}

/**
 * What a shared album accepts from [items]: library copies, or, for legacy albums and phone files, single files in a
 * supported format. Also counts the Live Photos left out, so the picker can say why they are missing.
 */
internal fun sharedContributionCandidates(items: List<GalleryMedia>, generation: SharedAlbumGeneration): Pair<List<GalleryMedia>, Int> {
    val personal = items.filter { !it.isShared && !it.isTrashed && !it.isHidden }
    val accepted = personal.filter {
        if (generation == SharedAlbumGeneration.LEGACY || it.origin == MediaOrigin.DEVICE) supportsLegacySharedUpload(it.kind, it.mimeType) else true
    }
    return accepted to (personal - accepted.toSet()).count { it.kind == MediaKind.LIVE_PHOTO }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedContributionPicker(items: List<GalleryMedia>, preview: (String) -> Unit, dismiss: () -> Unit,
    contribute: (List<String>, String) -> Unit, legacy: Boolean = false, requestPhotos: () -> Unit = {}, excludedLivePhotos: Int = 0) {
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var phone by rememberSaveable { mutableStateOf(false) }
    var posting by rememberSaveable { mutableStateOf(false) }
    val shown = items.filter { (it.origin == MediaOrigin.DEVICE) == phone }
    AlbaBottomSheet(onDismissRequest = dismiss, modifier = Modifier.testTag("shared-contribution-picker")) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(horizontal = AlbaSpacing.xl)) {
            AlbaSheetHeading(stringResource(R.string.shared_add_photos), dismiss = dismiss)
            Text(stringResource(R.string.shared_add_explanation), Modifier.padding(vertical = AlbaSpacing.md))
            if (legacy) Text(stringResource(R.string.shared_legacy_formats), Modifier.padding(bottom = AlbaSpacing.md), style = MaterialTheme.typography.bodySmall)
            if (legacy && excludedLivePhotos > 0) Text(pluralStringResource(R.plurals.shared_live_photos_excluded, excludedLivePhotos, excludedLivePhotos),
                Modifier.padding(bottom = AlbaSpacing.md).testTag("shared-live-photos-excluded"), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(AlbaSpacing.md)) {
                FilterChip(!phone, { phone = false }, label = { Text(stringResource(R.string.icloud)) })
                FilterChip(phone, { phone = true }, label = { Text(stringResource(R.string.shared_this_phone)) })
            }
            if (phone) TextButton(onClick = requestPhotos) { Text(stringResource(R.string.choose_phone_photos)) }
            if (shown.isEmpty()) Text(stringResource(R.string.shared_no_personal_photos))
            LazyVerticalGrid(columns = GridCells.Adaptive(96.dp), modifier = Modifier.weight(1f)) {
                items(shown.size, key = { shown[it].id }) { index ->
                    val media = shown[index]
                    LaunchedEffect(media.id) { if (media.previewReference == null) preview(media.id) }
                    Surface(onClick = { selected = if (media.id in selected) selected - media.id else if (selected.size < 50) selected + media.id else selected },
                        modifier = Modifier.padding(2.dp).aspectRatio(1f).testTag("shared-select-${media.id}")) {
                        Box {
                            MediaThumbnail(media.previewReference ?: media.viewerReference, media.accentStartArgb, media.accentEndArgb,
                                media.fileName, Modifier.fillMaxSize(), sourceRevision = media.sourceRevision)
                            Checkbox(checked = media.id in selected, onCheckedChange = null, modifier = Modifier.align(Alignment.TopEnd))
                            if (media.kind == MediaKind.VIDEO) MediaPill(Modifier.align(Alignment.BottomStart).padding(AlbaSpacing.xs)) {
                                Icon(Icons.Filled.PlayArrow, null, Modifier.size(14.dp))
                                mediaDuration(media.durationMillis)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                }
            }
            Button(onClick = { posting = true }, enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = AlbaSpacing.lg).testTag("shared-add-selected")) {
                Text(pluralStringResource(R.plurals.shared_add_selected, selected.size, selected.size))
            }
        }
    }
    if (posting) SharedPostSheet(selected.size, legacy, dismiss = { posting = false }) { caption -> posting = false; contribute(selected, caption) }
}

/** Confirms a post before anything is sent: how many items, and an optional caption where the album keeps one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedPostSheet(count: Int, legacy: Boolean, dismiss: () -> Unit, post: (String) -> Unit) {
    var caption by rememberSaveable { mutableStateOf("") }
    AlbaBottomSheet(onDismissRequest = dismiss, modifier = Modifier.testTag("shared-post-sheet")) {
        AlbaSheetBody(pluralStringResource(R.plurals.shared_post_items_title, count, count), dismiss) {
            if (legacy) Text(stringResource(R.string.shared_caption_legacy), style = MaterialTheme.typography.bodyMedium)
            else {
                OutlinedTextField(caption, { caption = it.take(MAX_CAPTION) }, label = { Text(stringResource(R.string.shared_caption)) },
                    modifier = Modifier.fillMaxWidth().testTag("shared-caption"))
                Text(stringResource(R.string.shared_caption_note), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { post(if (legacy) "" else caption.trim()) }, modifier = Modifier.fillMaxWidth().testTag("shared-post-confirm")) {
                Text(pluralStringResource(R.plurals.shared_post_items_title, count, count))
            }
        }
    }
}

private const val MAX_CAPTION = 1000

@Composable
internal fun CreateSharedAlbumDialog(dismiss: () -> Unit, create: (String, SharedAlbumGeneration) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var generation by rememberSaveable { mutableStateOf(SharedAlbumGeneration.MODERN) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.shared_create)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AlbaSpacing.md)) {
            OutlinedTextField(name, { name = it.take(255) }, label = { Text(stringResource(R.string.album_name)) }, singleLine = true, modifier = Modifier.testTag("shared-album-name"))
            SharedAlbumGeneration.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().testTag("shared-generation-$option").then(Modifier.selectable(selected = generation == option, onClick = { generation = option }, role = Role.RadioButton)), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = generation == option, onClick = null)
                    Text(stringResource(if(option == SharedAlbumGeneration.MODERN) R.string.shared_new_title else R.string.shared_legacy_title))
                }
            }
            Text(stringResource(if(generation == SharedAlbumGeneration.MODERN) R.string.shared_new_body else R.string.shared_legacy_body))
            Text(stringResource(R.string.shared_create_private_notice), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        TextButton(enabled = name.isNotBlank(), onClick = { create(name.trim(), generation) }, modifier = Modifier.testTag("shared-album-save")) {
            Text(stringResource(R.string.save))
        }
    },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
}

/** What an empty shared album offers: adding the first photos and inviting people, as far as the role allows. */
@Composable
internal fun SharedAlbumEmptyState(addPhotos: (() -> Unit)?, invite: (() -> Unit)?) {
    val addLabel = stringResource(R.string.shared_add_photos)
    val padding = Modifier.padding(horizontal = AlbaSpacing.xl, vertical = AlbaSpacing.xxl)
    Column(Modifier.fillMaxWidth().then(padding).testTag("shared-empty-state"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AlbaSpacing.md)) {
        Icon(painterResource(R.drawable.icloud_shared_albums), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.shared_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.shared_empty_body), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        addPhotos?.let {
            Button(onClick = it, modifier = Modifier.semantics { contentDescription = addLabel }.testTag("shared-empty-add")) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(AlbaSpacing.sm)); Text(addLabel)
            }
        }
        invite?.let {
            OutlinedButton(onClick = it, modifier = Modifier.testTag("shared-empty-invite")) {
                Icon(Icons.Outlined.Person, null, Modifier.size(18.dp))
                Spacer(Modifier.width(AlbaSpacing.sm))
                Text(stringResource(R.string.shared_invite_people))
            }
        }
    }
}
