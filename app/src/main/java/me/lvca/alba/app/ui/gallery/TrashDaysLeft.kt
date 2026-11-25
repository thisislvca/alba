package me.lvca.alba.app.ui.gallery

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.engine.model.GalleryMedia

/**
 * Whole days left before a trashed item is deleted for good, rounded up like Google Photos ("1 day" until it goes),
 * or null when the item is not in a trash or its source gives no expiry. Android's trash gives one, and iCloud's
 * Recently Deleted gives each asset's `dateExpunged`, which iCloud.com counts down to the same way.
 */
internal fun trashDaysLeft(media: GalleryMedia, nowEpochMillis: Long): Int? {
    val expires = media.expiresAtEpochMillis?.takeIf { media.isTrashed } ?: return null
    val left = expires - nowEpochMillis
    return ((left + DAY_MILLIS - 1).floorDiv(DAY_MILLIS)).toInt().coerceAtLeast(1)
}

/** "N days left" for a trashed item in the viewer, or null when [trashDaysLeft] has nothing to say. */
@Composable
internal fun trashDaysLeftLabel(media: GalleryMedia, nowEpochMillis: Long = System.currentTimeMillis()): String? =
    trashDaysLeft(media, nowEpochMillis)?.let { pluralStringResource(R.plurals.trash_days_left, it, it) }

private const val DAY_MILLIS = 86_400_000L

/** A grid tile's "N days" for a trashed item, Photos' wording on Recently Deleted tiles, or null like [trashDaysLeftLabel]. */
@Composable
internal fun trashTileDaysLabel(media: GalleryMedia, nowEpochMillis: Long = System.currentTimeMillis()): String? =
    trashDaysLeft(media, nowEpochMillis)?.let { pluralStringResource(R.plurals.trash_tile_days, it, it) }

/** Apple's note above iCloud's Recently Deleted: what the days on each tile mean. */
@Composable
internal fun RecentlyDeletedNote(modifier: Modifier = Modifier) {
    Text(stringResource(R.string.recently_deleted_note), modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
        .testTag("recently-deleted-note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
