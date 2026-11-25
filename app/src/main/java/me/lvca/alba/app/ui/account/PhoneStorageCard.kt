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
import me.lvca.alba.app.R
import me.lvca.alba.app.account.PhoneStorageState
import me.lvca.alba.app.ui.StorageActions
import me.lvca.alba.app.ui.designsystem.AlbaListGroup
import me.lvca.alba.app.ui.designsystem.AlbaListItem
import me.lvca.alba.app.ui.designsystem.AlbaListRow
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing
import me.lvca.alba.app.ui.localized
import me.lvca.alba.engine.model.LocalMediaCategory
import me.lvca.alba.engine.model.LocalMediaStorage

/** Alba's storage on this phone, with per-category cleanup. Shown as the profile's phone storage page. */
@Composable
internal fun PhoneStorageDetails(state: PhoneStorageState, busy: Boolean, actions: StorageActions) {
    var confirming by rememberSaveable { mutableStateOf<LocalMediaCategory?>(null) }
    LaunchedEffect(Unit) { actions.checkPhoneStorage() }
    val context = LocalContext.current
    fun bytes(size: Long) = Formatter.formatShortFileSize(context, size)
    Column(Modifier.testTag("phone-storage"), verticalArrangement = Arrangement.spacedBy(AlbaSpacing.xxl)) {
        state.usage?.let { usage ->
            Column {
                AlbaListGroup(title = stringResource(R.string.phone_storage_used_section)) {
                    AlbaListItem { Text(phoneStorageSummary(usage), style = MaterialTheme.typography.bodyLarge) }
                    usage.byCategory().forEach { (category, size) ->
                        AlbaListRow(stringResource(category.title()), onClick = null, subtitle = bytes(size), trailing = {
                            TextButton(onClick = { confirming = category }, enabled = size > 0 && !busy,
                                modifier = Modifier.testTag("clear-storage-$category")) { Text(stringResource(R.string.clear_storage)) }
                        }, contentPadding = PaddingValues(start = AlbaSpacing.lg, end = AlbaSpacing.xs, top = AlbaSpacing.sm, bottom = AlbaSpacing.sm))
                    }
                }
                Text(stringResource(R.string.thumbnails_retained_by_default), Modifier.padding(horizontal = AlbaSpacing.lg, vertical = AlbaSpacing.sm),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AlbaListGroup(title = stringResource(R.string.phone_storage_limits_section)) {
            StorageLimitSettings(state, enabled = !busy, chooseBudget = actions::setThumbnailBudget,
                chooseCacheLimit = actions::setCacheLimit)
        }
        Column(verticalArrangement = Arrangement.spacedBy(AlbaSpacing.sm)) {
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
