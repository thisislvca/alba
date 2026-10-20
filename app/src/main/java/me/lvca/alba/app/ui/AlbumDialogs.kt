package me.lvca.alba.app.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import me.lvca.alba.engine.model.*
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.theme.AlbaSpacing

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
