package me.lvca.alba.app

import kotlinx.coroutines.CancellationException

/** Runs [block] inside a diagnostics span that records success, failure or cancellation. */
internal suspend fun <T> AlbaDiagnostics.measure(
    operation: DiagnosticOperation,
    attributes: Map<DiagnosticAttribute, Any> = emptyMap(),
    block: suspend () -> T,
): T {
    val span = start(operation, attributes)
    return try {
        block().also { span.finish(DiagnosticOutcome.SUCCESS) }
    } catch (error: Throwable) {
        span.finish(
            if (error is CancellationException) DiagnosticOutcome.CANCELLED else DiagnosticOutcome.FAILED,
            error = error.takeUnless { it is CancellationException },
        )
        throw error
    }
}
