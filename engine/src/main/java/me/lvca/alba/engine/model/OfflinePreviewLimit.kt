package me.lvca.alba.engine.model

/** Decimal, as Android shows file sizes, so 500 MB reads as 500 MB. */
private const val MEGABYTE = 1_000L * 1_000L
private const val BYTES_250_MB = 250L * MEGABYTE
private const val BYTES_500_MB = 500L * MEGABYTE
private const val BYTES_1_GB = 1_000L * MEGABYTE
private const val BYTES_2_GB = 2_000L * MEGABYTE

/**
 * How much room the grid previews kept on the phone may take. Previews seen longest ago go first when the cache is
 * over the limit. [bytes] is null for [WHOLE_LIBRARY], which keeps every preview; low phone storage still trims it.
 * Stored by name: keep the names.
 */
enum class OfflinePreviewLimit(val bytes: Long?) {
    MB_250(BYTES_250_MB),
    MB_500(BYTES_500_MB),
    GB_1(BYTES_1_GB),
    GB_2(BYTES_2_GB),
    WHOLE_LIBRARY(null),
    ;

    companion object {
        val DEFAULT = MB_500
    }
}
