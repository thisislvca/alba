package me.lvca.alba.app.ui.collections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.designsystem.AlbaBottomSheet
import me.lvca.alba.app.ui.designsystem.AlbaChevron
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.AlbaListRow
import me.lvca.alba.app.ui.designsystem.AlbaSheetHeading
import me.lvca.alba.app.ui.designsystem.ICloudIcon
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing
import me.lvca.alba.engine.model.*

@Composable
internal fun AlbumNameDialog(title: String, initialName: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        OutlinedTextField(name, { if (it.length <= 255) name = it }, label = { Text(stringResource(R.string.album_name)) }, singleLine = true,
            modifier = Modifier.testTag("album-name-field"))
    }, confirmButton = {
        TextButton(enabled = name.isNotBlank(), onClick = { save(name.trim()) }, modifier = Modifier.testTag("album-name-save")) {
            Text(stringResource(R.string.save))
        }
    },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
}

/**
 * The folders and albums to offer at [folder]: albums directly in it, and folders that hold an album at some depth.
 * Empty folders are left out, since nothing in them can be chosen.
 */
internal fun albumChooserEntries(collections: List<GalleryCollection>, folder: String?): List<GalleryCollection> {
    val children = collections.filter { it.isPersonal }.groupBy { it.parentId }
    fun holdsAlbum(id: String, depth: Int = 0): Boolean = depth < MAX_FOLDER_DEPTH &&
        children[id].orEmpty().any { !it.isFolder || holdsAlbum(it.id, depth + 1) }
    return children[folder].orEmpty().filter { !it.isFolder || holdsAlbum(it.id) }
        .sortedWith(compareBy<GalleryCollection> { !it.isFolder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}

/** Deeper nesting than this is treated as a cycle in the folder data. */
private const val MAX_FOLDER_DEPTH = 32
private val rowPadding = PaddingValues(horizontal = AlbaSpacing.md, vertical = AlbaSpacing.sm)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumChooser(collections: List<GalleryCollection>, dismiss: () -> Unit, create: (String) -> Unit,
    title: String = stringResource(R.string.add_to_album), canCreate: Boolean = true, shared: Boolean = false, choose: (String) -> Unit) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    if (creating) AlbumNameDialog(stringResource(R.string.new_album), "", { creating = false }) { name ->
        creating = false; create(name)
    }
    AlbaBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            AlbaSheetHeading(title, subtitle = collections.firstOrNull { it.id == folder }?.name, dismiss = dismiss)
            // Shared albums have no folders: offer every one the person can add to.
            val entries = remember(collections, folder, shared) {
                if (shared) collections.filter { it.shared?.canContribute == true }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                else albumChooserEntries(collections, folder)
            }
            if (folder != null) AlbaListRow(stringResource(R.string.back_action), { folder = collections.firstOrNull { it.id == folder }?.parentId },
                leading = { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) }, shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = AlbaSpacing.md, vertical = AlbaSpacing.sm), modifier = Modifier.testTag("album-chooser-up"))
            else if (canCreate && !shared) TextButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().testTag("album-chooser-new")) {
                Text(stringResource(R.string.new_album))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), contentPadding = PaddingValues(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
                items(entries, key = { it.id }) { album ->
                    if (album.isFolder) AlbaListRow(album.name, { folder = album.id }, leading = { Icon(AlbaIcons.PhotoLibrary, null) },
                        trailing = { AlbaChevron() }, shape = MaterialTheme.shapes.small,
                        contentPadding = rowPadding, modifier = Modifier.testTag("album-chooser-folder-${album.id}"))
                    else AlbaListRow(album.name, { choose(album.id) },
                        leading = { ICloudIcon(if (shared) R.drawable.icloud_shared_albums else R.drawable.icloud_albums) }, shape = MaterialTheme.shapes.small,
                        contentPadding = rowPadding, modifier = Modifier.testTag("album-chooser-${album.id}"))
                }
            }
        }
    }
}
