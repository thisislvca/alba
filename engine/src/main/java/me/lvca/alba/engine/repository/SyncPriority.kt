package me.lvca.alba.engine.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.lvca.alba.engine.model.RefreshSummary
import java.util.concurrent.atomic.AtomicInteger

/**
 * Who goes first in the catalog. A library refresh runs once at a time, and a caller that finds one running joins it
 * rather than queueing a second behind it: the refresh on screen never waits for background work's to end and then
 * reads the library again. While a refresh runs, or a thumbnail or full-size image is wanted on screen, background
 * thumbnail filling waits between downloads.
 */
internal class SyncPriority {
    /** A refresh's result, and whether this caller joined one that was already running. */
    class Shared(val summary: RefreshSummary, val joined: Boolean)

    private val state = Mutex()
    private var running: CompletableDeferred<RefreshSummary>? = null
    private val foreground = AtomicInteger()

    /** False while a refresh runs or foreground work is in progress. */
    val backgroundTurn: Boolean get() = foreground.get() == 0

    /** Runs [block] ahead of background filling. */
    suspend fun <T> foreground(block: suspend () -> T): T {
        foreground.incrementAndGet()
        try { return block() } finally { foreground.decrementAndGet() }
    }

    /**
     * Runs [refresh], or joins the refresh already running and returns its result. When the refresh it joined was
     * cancelled, as when the system stops background work, this caller runs its own instead.
     */
    suspend fun shared(refresh: suspend () -> RefreshSummary): Shared {
        while (true) {
            val mine = CompletableDeferred<RefreshSummary>()
            val other = state.withLock { running.also { if (it == null) running = mine } }
            if (other == null) return Shared(run(mine, refresh), joined = false)
            await(other)?.let { return Shared(it, joined = true) }
        }
    }

    /** The result of the refresh running now, joined; null when none runs or it was cancelled. */
    suspend fun joinRunning(): RefreshSummary? = state.withLock { running }?.let { await(it) }

    /** [refresh]'s result; null when it was cancelled while this caller is still active. Its failure is this caller's. */
    private suspend fun await(refresh: CompletableDeferred<RefreshSummary>): RefreshSummary? {
        val outcome = runCatching { refresh.await() }
        val failure = outcome.exceptionOrNull() ?: return outcome.getOrThrow()
        if (failure !is CancellationException) throw failure
        currentCoroutineContext().ensureActive()
        return null
    }

    private suspend fun run(mine: CompletableDeferred<RefreshSummary>, refresh: suspend () -> RefreshSummary): RefreshSummary {
        try {
            // Completed either way, cancellation included, so callers that joined it never wait on it forever.
            val outcome = runCatching { foreground(refresh) }
            mine.completeWith(outcome)
            return outcome.getOrThrow()
        } finally {
            withContext(NonCancellable) { state.withLock { if (running === mine) running = null } }
        }
    }
}
