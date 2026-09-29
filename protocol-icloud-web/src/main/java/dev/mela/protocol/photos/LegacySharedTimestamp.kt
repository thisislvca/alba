package dev.mela.protocol.photos

import java.math.BigDecimal
import java.math.RoundingMode

// sharedstreams uses fractional seconds since 2001-01-01, unlike CloudKit's Unix milliseconds.
private val appleEpochMillis = BigDecimal("978307200000")

internal fun legacyCommentTimestamp(unixMillis: Long): String =
    BigDecimal.valueOf(unixMillis).subtract(appleEpochMillis).movePointLeft(3)
        .setScale(6).toPlainString()

internal fun legacyCommentTime(value: String?): Long = value?.let {
    runCatching {
        it.toBigDecimal().movePointRight(3).add(appleEpochMillis)
            .setScale(0, RoundingMode.DOWN).longValueExact()
    }.getOrNull()
} ?: 0L
