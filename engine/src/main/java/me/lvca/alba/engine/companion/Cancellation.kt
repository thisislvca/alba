package me.lvca.alba.engine.companion

import kotlinx.coroutines.CancellationException

/** [runCatching] for suspend work: failures become a [Result], but cancellation still propagates. */
internal inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}
