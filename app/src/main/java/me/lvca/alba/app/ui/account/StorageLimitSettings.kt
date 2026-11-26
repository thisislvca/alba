package me.lvca.alba.app.ui.account

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.account.OfflinePhotosStatus
import me.lvca.alba.app.account.OfflinePhotosWait
import me.lvca.alba.app.account.PhoneStorageState
import me.lvca.alba.app.ui.countLabel
import me.lvca.alba.app.ui.designsystem.AlbaListItem
import me.lvca.alba.app.ui.designsystem.AlbaListRow
import me.lvca.alba.engine.model.CacheLimit
import me.lvca.alba.engine.model.ThumbnailBudget

/**
 * The two stores of media on the phone: "Offline photos", the thumbnail budget that keeps photos browsable without a
 * connection, and the cache for full-size photos and videos. Each row opens a single-choice dialog. They are rows of
 * the [me.lvca.alba.app.ui.designsystem.AlbaListGroup] the caller puts them in.
 */
@Composable
internal fun StorageLimitSettings(
    state: PhoneStorageState,
    enabled: Boolean,
    chooseBudget: (ThumbnailBudget) -> Unit,
    chooseCacheLimit: (CacheLimit) -> Unit,
) {
    var choosing by rememberSaveable { mutableStateOf<String?>(null) }
    AlbaListRow(
        title = stringResource(R.string.offline_photos),
        subtitle = state.thumbnailBudget.label(),
        onClick = { choosing = BUDGET },
        enabled = enabled,
        modifier = Modifier.testTag("offline-photos-setting"),
    )
    state.offlinePhotos?.takeIf { it.progress.libraryCount > 0 }?.let { status ->
        OfflinePhotosDetails(status, enabled, chooseBudget)
    }
    AlbaListRow(
        title = stringResource(R.string.media_cache),
        subtitle = state.cacheLimit.label(),
        onClick = { choosing = CACHE },
        enabled = enabled,
        modifier = Modifier.testTag("media-cache-setting"),
    )
    state.usage?.let { usage ->
        AlbaListItem {
            Text(stringResource(R.string.media_cache_usage, Formatter.formatShortFileSize(LocalContext.current, usage.cacheBytes)),
                Modifier.testTag("media-cache-usage"), style = MaterialTheme.typography.bodyMedium)
        }
    }
    when (choosing) {
        BUDGET -> LimitDialog(R.string.offline_photos, R.string.offline_photos_explanation, "offline-photos",
            ThumbnailBudget.entries, state.thumbnailBudget, { it.label() }, { choosing = null }, chooseBudget)
        CACHE -> LimitDialog(R.string.media_cache, R.string.media_cache_explanation, "media-cache",
            CacheLimit.entries, state.cacheLimit, { it.label() }, { choosing = null }, chooseCacheLimit)
    }
}

/** "8,400 of 12,606 photos available offline", why it waits, and a larger budget when this one is too small. */
@Composable
private fun OfflinePhotosDetails(status: OfflinePhotosStatus, enabled: Boolean, chooseBudget: (ThumbnailBudget) -> Unit) {
    val context = LocalContext.current
    AlbaListItem {
        Text(stringResource(R.string.offline_photos_progress, countLabel(status.progress.savedCount),
            countLabel(status.progress.libraryCount)),
            Modifier.testTag("offline-photos-progress"), style = MaterialTheme.typography.bodyMedium)
        status.wait?.let { wait ->
            Text(stringResource(when (wait) {
                OfflinePhotosWait.WIFI_AND_CHARGING -> R.string.offline_photos_waiting
                OfflinePhotosWait.STORAGE_LOW -> R.string.offline_photos_storage_low
            }), Modifier.testTag("offline-photos-status"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        status.suggestedBudget?.let { suggested ->
            Text(stringResource(R.string.offline_photos_needs, Formatter.formatShortFileSize(context, status.libraryBytes)),
                Modifier.testTag("offline-photos-suggestion"), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { chooseBudget(suggested) }, enabled = enabled, modifier = Modifier.testTag("offline-photos-raise")) {
                Text(if (suggested == ThumbnailBudget.NO_LIMIT) stringResource(R.string.offline_photos_remove_limit)
                else stringResource(R.string.offline_photos_raise, suggested.label()))
            }
        }
    }
}

@Composable
private fun <T : Enum<T>> LimitDialog(
    title: Int,
    explanation: Int,
    tag: String,
    options: List<T>,
    current: T,
    label: @Composable (T) -> String,
    dismiss: () -> Unit,
    choose: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                Text(stringResource(explanation))
                Column(Modifier.selectableGroup().testTag("$tag-options")) {
                    options.forEach { option ->
                        AlbaListRow(
                            title = label(option),
                            selected = option == current,
                            onClick = { dismiss(); if (option != current) choose(option) },
                            leading = { RadioButton(selected = option == current, onClick = null) },
                            leadingSize = null,
                            contentPadding = PaddingValues(vertical = 4.dp),
                            minHeight = 48.dp,
                            modifier = Modifier.testTag("$tag-${option.name}"),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ThumbnailBudget.label(): String = stringResource(
    when (this) {
        ThumbnailBudget.GB_1 -> R.string.storage_limit_1_gb
        ThumbnailBudget.GB_2 -> R.string.storage_limit_2_gb
        ThumbnailBudget.GB_4 -> R.string.storage_limit_4_gb
        ThumbnailBudget.NO_LIMIT -> R.string.storage_limit_none
    },
)

@Composable
private fun CacheLimit.label(): String = stringResource(
    when (this) {
        CacheLimit.MB_250 -> R.string.storage_limit_250_mb
        CacheLimit.MB_500 -> R.string.storage_limit_500_mb
        CacheLimit.GB_1 -> R.string.storage_limit_1_gb
        CacheLimit.GB_2 -> R.string.storage_limit_2_gb
    },
)

private const val BUDGET = "budget"
private const val CACHE = "cache"
