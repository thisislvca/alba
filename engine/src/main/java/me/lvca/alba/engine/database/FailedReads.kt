package me.lvca.alba.engine.database

import android.database.sqlite.SQLiteException
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen

/**
 * An observed read that fails is read again after a pause instead of failing its collector, which for the library
 * screen ended the process. The large reads are consistent on their own (see [CatalogDao]); this is the backstop for a
 * read the database still fails, so the screen keeps what it showed and catches up once a read succeeds.
 */
internal fun <T> Flow<T>.retryFailedReads(what: String): Flow<T> = retryWhen { cause, attempt ->
    if (!cause.isFailedRead()) return@retryWhen false
    Log.w(TAG, "Reading $what failed; reading it again", cause)
    delay(RETRY_BASE_MILLIS shl attempt.coerceAtMost(MAX_RETRY_SHIFT).toInt())
    true
}

/** A query the database could not run or a cursor it could not fill, as opposed to a bug in the code reading it. */
internal fun Throwable.isFailedRead(): Boolean = this is SQLiteException ||
    (this is IllegalStateException && message.orEmpty().contains("CursorWindow"))

private const val TAG = "AlbaDatabase"
private const val RETRY_BASE_MILLIS = 100L
private const val MAX_RETRY_SHIFT = 6L
