package me.lvca.alba.app.ui.account

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import me.lvca.alba.app.R
import me.lvca.alba.engine.model.CloudStorageUsage
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/**
 * iCloud sizes the way Apple shows them: counted in 1024s and labelled KB, MB, GB and TB, so a 2 TiB plan reads
 * "2 TB" as it does on an iPhone. Sizes on this phone keep Android's own convention (Formatter.formatShortFileSize).
 */
internal enum class ICloudSizeUnit(@param:StringRes val label: Int) {
    BYTES(R.string.icloud_size_bytes),
    KB(R.string.icloud_size_kb),
    MB(R.string.icloud_size_mb),
    GB(R.string.icloud_size_gb),
    TB(R.string.icloud_size_tb),
    PB(R.string.icloud_size_pb),
}

private const val STEP = 1024.0
private const val WHOLE_NUMBERS_FROM = 100.0

/** [bytes] as a number in [locale] and the Apple unit it is counted in. */
internal fun icloudSizeParts(bytes: Long, locale: Locale): Pair<String, ICloudSizeUnit> {
    val units = ICloudSizeUnit.entries
    var value = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    while (unit < units.lastIndex && value >= STEP) { value /= STEP; unit++ }
    // One decimal below 100 ("1.9 TB", "12.3 GB"), whole numbers above ("423 GB"), none for bytes.
    val decimals = if (unit == 0 || value >= WHOLE_NUMBERS_FROM) 0 else 1
    var rounded = value.toBigDecimal().setScale(decimals, RoundingMode.HALF_UP).toDouble()
    if (rounded >= STEP && unit < units.lastIndex) { rounded = 1.0; unit++ }
    val number = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = decimals
    }.format(rounded)
    return number to units[unit]
}

internal fun Resources.icloudSize(bytes: Long): String {
    val (number, unit) = icloudSizeParts(bytes, configuration.locales[0])
    return getString(unit.label, number)
}

@Composable
internal fun icloudSize(bytes: Long): String {
    val (number, unit) = icloudSizeParts(bytes, LocalConfiguration.current.locales[0])
    return stringResource(unit.label, number)
}

/** One part of the iCloud usage bar and its legend. */
internal sealed interface StorageSegment {
    val bytes: Long

    /** One of the account's own categories, by Apple's label. */
    data class Category(val name: String, override val bytes: Long) : StorageSegment

    /** What the rest of the Family Sharing group uses of a shared plan. */
    data class Family(override val bytes: Long) : StorageSegment

    /** Used space Apple doesn't put in a category. */
    data class Other(override val bytes: Long) : StorageSegment
}

private val OtherLabels = setOf("other", "others")

/**
 * The account's categories (largest first), then Family, then Other, adding up to exactly [CloudStorageUsage.usedBytes].
 * Figures that overrun what is used are trimmed in that order; whatever is left is Other, together with Apple's own
 * "Other" category.
 */
internal fun CloudStorageUsage.storageSegments(): List<StorageSegment> {
    var remaining = usedBytes.coerceAtLeast(0)
    fun take(bytes: Long): Long = bytes.coerceIn(0, remaining).also { remaining -= it }
    val categories = categories.filter { it.bytes > 0 && it.name.trim().lowercase(Locale.ROOT) !in OtherLabels }
        .sortedByDescending { it.bytes }
        .map { StorageSegment.Category(it.name, take(it.bytes)) }
    val family = StorageSegment.Family(take(familyBytes))
    return (categories + family + StorageSegment.Other(remaining)).filter { it.bytes > 0 }
}
