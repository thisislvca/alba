package dev.mela.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.mela.app.R
import dev.mela.app.UiMessage
import dev.mela.engine.model.GalleryCollection
import dev.mela.engine.model.SmartCollection
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable internal fun UiMessage.localized(): String {
    LocalConfiguration.current
    return resolve(LocalContext.current)
}
@Composable internal fun itemCount(count: Int) = pluralStringResource(R.plurals.item_count, count, count)
@Composable internal fun monthLabel(month: YearMonth, abbreviated: Boolean = false): String = month.format(DateTimeFormatter.ofPattern(if (abbreviated) "LLL yyyy" else "MMMM yyyy", LocalConfiguration.current.locales[0]))
@Composable internal fun dateLabel(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LocalConfiguration.current.locales[0]))
@Composable internal fun timestampLabel(instant: Instant): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withLocale(LocalConfiguration.current.locales[0]).withZone(ZoneId.systemDefault()).format(instant)
@Composable internal fun SmartCollection.localizedTitle(): String = stringResource(when (this) {
    SmartCollection.VIDEOS -> R.string.videos
    SmartCollection.LIVE_PHOTOS -> R.string.live_photos
    SmartCollection.SCREENSHOTS -> R.string.screenshots
    SmartCollection.PANORAMAS -> R.string.panoramas
    SmartCollection.BURSTS -> R.string.bursts
    SmartCollection.SLO_MO -> R.string.slo_mo
    SmartCollection.TIME_LAPSE -> R.string.time_lapse
})
@Composable internal fun GalleryCollection.localizedName(): String = SmartCollection.entries.firstOrNull { it.id == id }?.localizedTitle() ?: name
@Composable internal fun storageCategoryLabel(name: String): String = when (name.lowercase(java.util.Locale.ROOT)) {
    "photos", "icloud photos" -> stringResource(R.string.photos)
    "backups", "backup" -> stringResource(R.string.backups)
    "documents", "docs" -> stringResource(R.string.documents)
    "mail" -> stringResource(R.string.mail)
    "other", "others" -> stringResource(R.string.other)
    else -> name // Provider-supplied labels are data, like album names and filenames.
}
