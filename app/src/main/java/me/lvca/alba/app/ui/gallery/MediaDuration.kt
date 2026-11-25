package me.lvca.alba.app.ui.gallery

/**
 * A video's length as iCloud.com shows it: rounded to the nearest second (10.24 s is 0:10, 43.74 s is 0:44).
 * Unknown durations stay unknown; hours do not wrap back to zero minutes.
 */
internal fun mediaDuration(durationMillis: Long?): String? {
    if (durationMillis == null || durationMillis < 0) return null
    val seconds = (durationMillis + HALF_SECOND_MILLIS) / SECOND_MILLIS
    val minutes = seconds / 60
    val tail = (seconds % 60).toString().padStart(2, '0')
    return if (minutes < 60) "$minutes:$tail" else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$tail"
}

private const val SECOND_MILLIS = 1000L
private const val HALF_SECOND_MILLIS = 500L
