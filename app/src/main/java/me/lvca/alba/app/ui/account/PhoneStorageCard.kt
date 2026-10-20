package me.lvca.alba.app.ui.account

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.lvca.alba.engine.model.LocalMediaCategory
import me.lvca.alba.engine.model.LocalMediaStorage
import me.lvca.alba.app.R
import me.lvca.alba.app.account.PhoneStorageState
import me.lvca.alba.app.ui.StorageActions
import me.lvca.alba.app.ui.designsystem.AlbaCard
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing
import me.lvca.alba.app.ui.localized

/** Alba's storage on this phone, with per-category cleanup. Shown as the profile's phone storage page. */
@Composable
internal fun PhoneStorageDetails(state: PhoneStorageState, busy: Boolean, actions: StorageActions) {
    var confirming by rememberSaveable { mutableStateOf<LocalMediaCategory?>(null) }
    LaunchedEffect(Unit) { actions.checkPhoneStorage() }
    val context = LocalContext.current
    fun bytes(size: Long) = Formatter.formatShortFileSize(context, size)
    AlbaCard(Modifier.testTag("phone-storage")) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(AlbaSpacing.sm)) {
            Text(stringResource(R.string.phone_storage_title), style = MaterialTheme.typography.titleMedium)
            state.usage?.let { usage ->
                Text(phoneStorageSummary(usage))
                usage.byCategory().forEach { (category, size) ->
                    HorizontalDivider()
                    Text(stringResource(category.title()), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(bytes(size), Modifier.weight(1f))
                        TextButton(onClick = { confirming = category }, enabled = size > 0 && !busy,
                            modifier = Modifier.testTag("clear-storage-$category")) { Text(stringResource(R.string.clear_storage)) }
                    }
                }
                Text(stringResource(R.string.thumbnails_retained_by_default), style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { Text(it.localized(), color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = actions::checkPhoneStorage, enabled = !state.checking && !busy,
                modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (state.checking) R.string.checking else R.string.refresh_phone_storage))
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

/** What Alba keeps on the phone, per category, in the order every storage view lists it. */
internal fun LocalMediaStorage.byCategory(): List<Pair<LocalMediaCategory, Long>> = LocalMediaCategory.entries.map { category ->
    category to when (category) {
        LocalMediaCategory.PREVIEWS -> previewBytes
        LocalMediaCategory.VIEWERS -> viewerBytes
        LocalMediaCategory.ORIGINALS -> originalBytes
    }
}

/** "Alba uses X; Y free on this phone." */
@Composable
internal fun phoneStorageSummary(usage: LocalMediaStorage): String {
    val context = LocalContext.current
    return stringResource(R.string.phone_storage_usage, Formatter.formatShortFileSize(context, usage.totalBytes),
        Formatter.formatShortFileSize(context, usage.availableBytes))
}

internal fun LocalMediaCategory.title(): Int = when (this) {
    LocalMediaCategory.VIEWERS -> R.string.viewing_cache
    LocalMediaCategory.PREVIEWS -> R.string.saved_thumbnails
    LocalMediaCategory.ORIGINALS -> R.string.offline_originals
}
