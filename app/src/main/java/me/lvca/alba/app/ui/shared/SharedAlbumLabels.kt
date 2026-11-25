package me.lvca.alba.app.ui.shared

import android.icu.text.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.currentLocale
import me.lvca.alba.engine.model.SharedAlbumInfo
import me.lvca.alba.engine.model.SharedAlbumRole
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/*
 * The lines iCloud Photos shows under a shared album, in Apple's wording: who created it, or for the account's own albums
 * who joined it, and when a temporary album expires.
 */

/** The line under a shared album in a list: "From <owner>", or for an album of this account "Created by You" or who joined. */
@Composable
internal fun sharedAlbumListLine(info: SharedAlbumInfo): String? = when {
    info.role == SharedAlbumRole.OWNER -> when (info.sharedWith.size) {
        0 -> stringResource(R.string.shared_created_by_you)
        1 -> stringResource(R.string.shared_with_person, info.sharedWith.single())
        else -> pluralStringResource(R.plurals.shared_with_people, info.sharedWith.size, info.sharedWith.size)
    }
    else -> info.ownerName?.let { stringResource(R.string.shared_from, it) }
}

/** Who created a shared album, as its header says it: "Created by You" or "Created by <owner>". */
@Composable
internal fun sharedAlbumCreator(info: SharedAlbumInfo): String? =
    if (info.role == SharedAlbumRole.OWNER) stringResource(R.string.shared_created_by_you)
    else info.ownerName?.let { stringResource(R.string.shared_created_by, it) }

/** "Expires December 8" for a temporary album, or null for one that stays. */
@Composable
internal fun sharedAlbumExpiry(info: SharedAlbumInfo): String? =
    info.expiresAt?.let { stringResource(R.string.shared_expires, expiryDateLabel(it, currentLocale())) }

/** The day an album expires, without the year when it is this year: "December 8", "8 dicembre", "December 8, 2027". */
internal fun expiryDateLabel(
    epochMillis: Long,
    locale: Locale,
    nowEpochMillis: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val year = { millis: Long -> Instant.ofEpochMilli(millis).atZone(zone).year }
    val format = DateFormat.getInstanceForSkeleton(if (year(epochMillis) == year(nowEpochMillis)) "MMMMd" else "yMMMMd", locale)
    format.timeZone = android.icu.util.TimeZone.getTimeZone(zone.id)
    return format.format(Date(epochMillis))
}
