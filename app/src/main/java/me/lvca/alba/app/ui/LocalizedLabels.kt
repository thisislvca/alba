package me.lvca.alba.app.ui

import android.icu.text.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import me.lvca.alba.app.R
import me.lvca.alba.app.UiMessage
import me.lvca.alba.engine.model.GalleryCollection
import me.lvca.alba.engine.model.SmartCollection
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

@Composable internal fun UiMessage.localized(): String {
    LocalConfiguration.current
    return resolve(LocalContext.current)
}
@Composable internal fun itemCount(count: Int) = pluralStringResource(R.plurals.item_count, count, count)

/*
 * Every date and time shown in the UI goes through these helpers. Skeletons ("MMMd", "Hm") let each
 * locale choose its own field order and separators, and times follow the user's 12/24-hour setting.
 */
private val skeletonFormats = HashMap<Pair<String, Locale>, DateFormat>()

private fun formatSkeleton(skeleton: String, locale: Locale, epochMillis: Long): String = synchronized(skeletonFormats) {
    skeletonFormats.getOrPut(skeleton to locale) { DateFormat.getInstanceForSkeleton(skeleton, locale) }.format(Date(epochMillis))
}

@Composable private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]
@Composable private fun uses24HourClock(): Boolean = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
private fun YearMonth.epochMillis(): Long = atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** "October 2026", or "Oct 2026" when [abbreviated], in the locale's order. */
@Composable internal fun monthLabel(month: YearMonth, abbreviated: Boolean = false): String =
    formatSkeleton(if (abbreviated) "MMMy" else "MMMMy", currentLocale(), month.epochMillis())
/** The stand-alone month name, for layouts that show the year separately. */
@Composable internal fun monthNameLabel(month: YearMonth): String = formatSkeleton("LLLL", currentLocale(), month.epochMillis())
/** Day and abbreviated month without the year: "Oct 20" in English, "20 ott" in Italian. */
@Composable internal fun dayMonthLabel(epochMillis: Long): String = formatSkeleton("MMMd", currentLocale(), epochMillis)
/** Hours and minutes in the user's 12/24-hour preference. */
@Composable internal fun timeLabel(epochMillis: Long): String =
    formatSkeleton(if (uses24HourClock()) "Hm" else "hm", currentLocale(), epochMillis)
@Composable internal fun dateLabel(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(currentLocale()))
@Composable internal fun timestampLabel(epochMillis: Long): String =
    formatSkeleton(if (uses24HourClock()) "yMMMdHm" else "yMMMdhm", currentLocale(), epochMillis)
@Composable internal fun timestampLabel(instant: Instant): String = timestampLabel(instant.toEpochMilli())
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
@Composable internal fun storageCategoryLabel(name: String): String = when (name.lowercase(Locale.ROOT)) {
    "photos", "icloud photos" -> stringResource(R.string.photos)
    "backups", "backup" -> stringResource(R.string.backups)
    "documents", "docs" -> stringResource(R.string.documents)
    "mail" -> stringResource(R.string.mail)
    "other", "others" -> stringResource(R.string.other)
    else -> name // Provider-supplied labels are data, like album names and filenames.
}
