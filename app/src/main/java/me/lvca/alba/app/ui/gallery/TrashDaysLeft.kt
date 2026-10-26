package me.lvca.alba.app.ui.gallery

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import me.lvca.alba.app.R
import me.lvca.alba.engine.model.GalleryMedia

/**
 * Whole days left before a trashed item is deleted for good, rounded up like Google Photos ("1 day" until it goes),
 * or null when the item is not in a trash or its source gives no expiry. Android's trash gives one; iCloud's web
 * service does not expose it to Alba yet.
 */
internal fun trashDaysLeft(media: GalleryMedia, nowEpochMillis: Long): Int? {
    val expires = media.expiresAtEpochMillis?.takeIf { media.isTrashed } ?: return null
    val left = expires - nowEpochMillis
    return ((left + DAY_MILLIS - 1).floorDiv(DAY_MILLIS)).toInt().coerceAtLeast(1)
}

/** "N days left" for a trashed item, or null when [trashDaysLeft] has nothing to say. */
@Composable
internal fun trashDaysLeftLabel(media: GalleryMedia, nowEpochMillis: Long = System.currentTimeMillis()): String? =
    trashDaysLeft(media, nowEpochMillis)?.let { pluralStringResource(R.plurals.trash_days_left, it, it) }

private const val DAY_MILLIS = 86_400_000L
