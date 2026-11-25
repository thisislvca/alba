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
import me.lvca.alba.app.account.PhoneStorageState
import me.lvca.alba.app.ui.designsystem.AlbaListRow
import me.lvca.alba.engine.model.OfflinePreviewLimit

/**
 * "Offline previews": how much room the thumbnails kept for browsing without a connection may take. A row with the
 * current choice opens a single-choice dialog; what previews use now and what the whole library would need are below.
 */
@Composable
internal fun OfflinePreviewsSetting(state: PhoneStorageState, enabled: Boolean, choose: (OfflinePreviewLimit) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    fun bytes(size: Long) = Formatter.formatShortFileSize(context, size)
    val wholeLibrary = state.wholeLibraryPreviewBytes
    AlbaListRow(
        title = stringResource(R.string.offline_previews),
        subtitle = state.previewLimit.label(),
        onClick = { choosing = true },
        enabled = enabled,
        contentPadding = PaddingValues(vertical = 12.dp),
        modifier = Modifier.testTag("offline-previews-setting"),
    )
    state.usage?.let { usage ->
        Text(
            if (wholeLibrary != null) {
                stringResource(R.string.offline_previews_usage_estimate, bytes(usage.previewBytes), bytes(wholeLibrary))
            } else {
                stringResource(R.string.offline_previews_usage, bytes(usage.previewBytes))
            },
            Modifier.testTag("offline-previews-usage"),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.offline_previews)) },
            text = {
                Column {
                    Text(stringResource(R.string.offline_previews_explanation))
                    Column(Modifier.selectableGroup().testTag("offline-previews-options")) {
                        OfflinePreviewLimit.entries.forEach { limit ->
                            val title = if (limit == OfflinePreviewLimit.WHOLE_LIBRARY && wholeLibrary != null) {
                                stringResource(R.string.offline_previews_whole_library_estimate, bytes(wholeLibrary))
                            } else {
                                limit.label()
                            }
                            AlbaListRow(
                                title = title,
                                selected = limit == state.previewLimit,
                                onClick = { choosing = false; if (limit != state.previewLimit) choose(limit) },
                                leading = { RadioButton(selected = limit == state.previewLimit, onClick = null) },
                                leadingSize = null,
                                contentPadding = PaddingValues(vertical = 4.dp),
                                minHeight = 48.dp,
                                modifier = Modifier.testTag("offline-previews-${limit.name}"),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun OfflinePreviewLimit.label(): String = stringResource(
    when (this) {
        OfflinePreviewLimit.MB_250 -> R.string.offline_previews_250_mb
        OfflinePreviewLimit.MB_500 -> R.string.offline_previews_500_mb
        OfflinePreviewLimit.GB_1 -> R.string.offline_previews_1_gb
        OfflinePreviewLimit.GB_2 -> R.string.offline_previews_2_gb
        OfflinePreviewLimit.WHOLE_LIBRARY -> R.string.offline_previews_whole_library
    },
)
