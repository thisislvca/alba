package dev.mela.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mela.app.R
import dev.mela.engine.model.*

@Composable
internal fun SharedAlbumBadge(info: SharedAlbumInfo, click: () -> Unit) {
    AssistChip(onClick = click, label = { Text(stringResource(if (info.generation == SharedAlbumGeneration.MODERN) R.string.shared_new else R.string.shared_legacy)) },
        leadingIcon = { Icon(Icons.Outlined.Info, null, Modifier.size(16.dp)) }, modifier = Modifier.testTag("shared-version-badge"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedAlbumDetails(album: GalleryCollection, dismiss: () -> Unit) {
    val info = requireNotNull(album.shared)
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    MelaBottomSheet(onDismissRequest = dismiss, modifier = Modifier.testTag("shared-album-details")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            MelaSheetHeading(album.name, dismiss = dismiss)
            Text(stringResource(if (info.generation == SharedAlbumGeneration.MODERN) R.string.shared_new_title else R.string.shared_legacy_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(if (info.generation == SharedAlbumGeneration.MODERN) R.string.shared_new_body else R.string.shared_legacy_body), style = MaterialTheme.typography.bodyLarge)
            Surface(shape = RoundedCornerShape(22.dp), color = melaGroupColor()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(when(info.role) {
                        SharedAlbumRole.MANAGER -> R.string.shared_role_manager
                        SharedAlbumRole.COMMENTER -> R.string.shared_role_commenter
                        SharedAlbumRole.OWNER -> R.string.shared_role_owner
                        SharedAlbumRole.CONTRIBUTOR -> R.string.shared_role_contributor
                        SharedAlbumRole.VIEWER -> R.string.shared_role_viewer
                    }), style = MaterialTheme.typography.titleMedium)
                    info.ownerName?.let { Text(stringResource(R.string.shared_owner, it)) }
                    info.participantCount?.let { Text(androidx.compose.ui.res.pluralStringResource(R.plurals.shared_participants, it, it)) }
                    Text(stringResource(R.string.shared_copy_notice), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(stringResource(R.string.shared_upgrade_notice), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            info.webUrl?.let { url ->
                Text(stringResource(R.string.shared_manage_explanation), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { uriHandler.openUri(url) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.shared_manage_icloud)) }
            }
            Button(onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.shared_understood)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedContributionPicker(items: List<GalleryMedia>, preview: (String) -> Unit, dismiss: () -> Unit, contribute: (List<String>) -> Unit, legacy: Boolean = false, requestPhotos: () -> Unit = {}) {
    var selected by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(emptyList<String>()) }
    var phone by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val shown = items.filter { (it.origin == MediaOrigin.DEVICE) == phone }
    MelaBottomSheet(onDismissRequest = dismiss, modifier = Modifier.testTag("shared-contribution-picker")) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(horizontal = 20.dp)) {
            MelaSheetHeading(stringResource(R.string.shared_add_photos), dismiss = dismiss)
            Text(stringResource(R.string.shared_add_explanation), Modifier.padding(vertical = 12.dp))
            if (legacy) Text(stringResource(R.string.shared_legacy_formats), Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(!phone, { phone = false }, label = { Text(stringResource(R.string.icloud)) })
                FilterChip(phone, { phone = true }, label = { Text(stringResource(R.string.shared_this_phone)) })
            }
            if (phone) TextButton(onClick = requestPhotos) { Text(stringResource(R.string.choose_phone_photos)) }
            if (shown.isEmpty()) Text(stringResource(R.string.shared_no_personal_photos))
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(96.dp), modifier = Modifier.weight(1f)) {
                items(shown.size, key = { shown[it].id }) { index ->
                    val media = shown[index]
                    androidx.compose.runtime.LaunchedEffect(media.id) { if (media.previewReference == null) preview(media.id) }
                    Surface(onClick = { selected = if (media.id in selected) selected - media.id else if (selected.size < 50) selected + media.id else selected },
                        modifier = Modifier.padding(2.dp).aspectRatio(1f).testTag("shared-select-${media.id}")) {
                        Box {
                            MediaThumbnail(media.previewReference ?: media.viewerReference, media.accentStartArgb, media.accentEndArgb,
                                media.fileName, Modifier.fillMaxSize(), sourceRevision = media.sourceRevision)
                            Checkbox(checked = media.id in selected, onCheckedChange = null, modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd))
                        }
                    }
                }
            }
            Button(onClick = { contribute(selected) }, enabled = selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 16.dp)) {
                Text(androidx.compose.ui.res.pluralStringResource(R.plurals.shared_add_selected, selected.size, selected.size))
            }
        }
    }
}

@Composable
internal fun CreateSharedAlbumDialog(dismiss: () -> Unit, create: (String, SharedAlbumGeneration) -> Unit) {
    var name by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    var generation by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(SharedAlbumGeneration.MODERN) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.shared_create)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(255) }, label = { Text(stringResource(R.string.album_name)) }, singleLine = true, modifier = Modifier.testTag("shared-album-name"))
            SharedAlbumGeneration.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().testTag("shared-generation-$option").then(Modifier.selectable(selected = generation == option, onClick = { generation = option }, role = androidx.compose.ui.semantics.Role.RadioButton)), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = generation == option, onClick = null)
                    Text(stringResource(if(option == SharedAlbumGeneration.MODERN) R.string.shared_new_title else R.string.shared_legacy_title))
                }
            }
            Text(stringResource(if(generation == SharedAlbumGeneration.MODERN) R.string.shared_new_body else R.string.shared_legacy_body))
            Text(stringResource(R.string.shared_create_private_notice), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { create(name.trim(), generation) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
}
