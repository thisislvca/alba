package dev.mela.app.ui

/** Unknown durations stay unknown; hours do not wrap back to zero minutes. */
internal fun mediaDuration(durationMillis: Long?): String? {
    if (durationMillis == null || durationMillis < 0) return null
    val seconds = durationMillis / 1000
    val minutes = seconds / 60
    val tail = (seconds % 60).toString().padStart(2, '0')
    return if (minutes < 60) "$minutes:$tail" else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$tail"
}
