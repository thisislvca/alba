package me.lvca.alba.app.ui.gallery

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.designsystem.AlbaCard
import me.lvca.alba.app.ui.designsystem.AlbaIcons

/** Android 14's partial photo access: how many phone photos Alba can see, a way to change them, and a close button. */
@Composable
internal fun LimitedAccessNote(count: Int, change: () -> Unit, dismiss: () -> Unit, modifier: Modifier = Modifier) {
    AlbaCard(modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("limited-access-note"),
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AlbaIcons.PhoneAndroid, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(pluralStringResource(R.plurals.limited_access_note, count, count), Modifier.weight(1f).padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = change, modifier = Modifier.testTag("limited-access-change")) {
                Text(stringResource(R.string.limited_access_change))
            }
            IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.limited_access_dismiss)) }
        }
    }
}
