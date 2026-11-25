package me.lvca.alba.app.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.gallery.LibraryPreparing
import me.lvca.alba.app.ui.designsystem.AlbaCard
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.theme.AlbaIconSize

/** The header's status line while Apple is still indexing the library, with its percentage when Apple said. */
@Composable
internal fun libraryPreparingLabel(preparing: LibraryPreparing): String =
    preparing.progress?.let { stringResource(R.string.library_preparing_status_progress, it) }
        ?: stringResource(R.string.library_preparing_status)

/**
 * Apple is still indexing the iCloud library: a calm, lasting note above the phone's own photos rather than an error
 * and an empty library. Alba checks again by itself; [checkNow] is there for the impatient. Nothing in it moves, and
 * the bar only shows a percentage Apple reported.
 */
@Composable
internal fun LibraryPreparingCard(preparing: LibraryPreparing, checking: Boolean, checkNow: () -> Unit, modifier: Modifier = Modifier) {
    AlbaCard(modifier.testTag("library-preparing"), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(AlbaIcons.CloudQueue, null, Modifier.padding(top = 2.dp).size(AlbaIconSize.standard), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.library_preparing_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.library_preparing_body), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                preparing.progress?.let { progress ->
                    LinearProgressIndicator(progress = { progress / PERCENT }, modifier = Modifier.fillMaxWidth().testTag("library-preparing-bar"))
                    Text(stringResource(R.string.library_preparing_progress, progress), Modifier.testTag("library-preparing-progress"),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = checkNow, enabled = !checking, contentPadding = PaddingValues(horizontal = 0.dp),
                    modifier = Modifier.testTag("library-preparing-check")) {
                    Text(stringResource(if (checking) R.string.checking else R.string.library_preparing_check_now))
                }
            }
        }
    }
}

private const val PERCENT = 100f
