package dev.mela.app.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mela.app.GalleryUiState
import dev.mela.app.R
import dev.mela.engine.model.LocalMediaCategory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneStorageCard(state: GalleryUiState, actions: LibraryActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("phone-storage-button")) {
        Text(stringResource(R.string.phone_storage_title))
    }
    if (open) MelaBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
            PhoneStorageDetails(state, actions)
        }
    }
}

@Composable
internal fun PhoneStorageDetails(state: GalleryUiState, actions: LibraryActions) {
    var confirming by rememberSaveable { mutableStateOf<LocalMediaCategory?>(null) }
    LaunchedEffect(Unit) { actions.checkPhoneStorage() }
    val context = LocalContext.current
    fun bytes(size: Long) = Formatter.formatShortFileSize(context, size)
    val busy = state.activeDownloadId != null || state.accountBusy
    Card(Modifier.fillMaxWidth().testTag("phone-storage"), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = melaGroupColor())) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.phone_storage_title), style = MaterialTheme.typography.titleMedium)
            state.phoneStorage?.let { usage ->
                Text(stringResource(R.string.phone_storage_usage, bytes(usage.totalBytes), bytes(usage.availableBytes)))
                LocalMediaCategory.entries.forEach { category ->
                    val size = when (category) {
                        LocalMediaCategory.PREVIEWS -> usage.previewBytes
                        LocalMediaCategory.VIEWERS -> usage.viewerBytes
                        LocalMediaCategory.ORIGINALS -> usage.originalBytes
                    }
                    HorizontalDivider()
                    Text(stringResource(category.title()), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(bytes(size), Modifier.weight(1f))
                        TextButton(onClick = { confirming = category }, enabled = size > 0 && !busy,
                            modifier = Modifier.testTag("clear-storage-$category")) { Text(stringResource(R.string.clear_storage)) }
                    }
                }
                Text(stringResource(R.string.thumbnails_retained_by_default), style = MaterialTheme.typography.bodySmall)
            }
            state.phoneStorageError?.let { Text(it.localized(), color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = actions.checkPhoneStorage, enabled = !state.phoneStorageChecking && !busy,
                modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (state.phoneStorageChecking) R.string.checking else R.string.refresh_phone_storage))
            }
        }
    }
    confirming?.let { category ->
        AlertDialog(onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.clear_storage_title, stringResource(category.title()))) },
            text = { Text(stringResource(when (category) {
                LocalMediaCategory.VIEWERS -> R.string.clear_viewers_explanation
                LocalMediaCategory.PREVIEWS -> R.string.clear_thumbnails_explanation
                LocalMediaCategory.ORIGINALS -> R.string.clear_originals_explanation
            })) },
            confirmButton = { TextButton(enabled = !busy, onClick = { confirming = null; actions.clearLocalMedia(category) },
                modifier = Modifier.testTag("confirm-clear-storage")) { Text(stringResource(R.string.clear_storage)) } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

private fun LocalMediaCategory.title(): Int = when (this) {
    LocalMediaCategory.VIEWERS -> R.string.viewing_cache
    LocalMediaCategory.PREVIEWS -> R.string.saved_thumbnails
    LocalMediaCategory.ORIGINALS -> R.string.offline_originals
}
