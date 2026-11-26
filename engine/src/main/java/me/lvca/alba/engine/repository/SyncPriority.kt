package me.lvca.alba.engine.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import me.lvca.alba.engine.model.RefreshSummary

/**
 * Who goes first in the catalog. A library refresh runs once at a time, and a caller that finds one running joins it
 * rather than queueing a second behind it: the refresh on screen never waits for background work's to end and then
 * reads the library again. While a refresh runs, or a thumbnail or full-size image is wanted on screen, background
 * thumbnail filling waits between downloads.
 */
internal class SyncPriority {
    /** A refresh's result, and whether this caller joined one that was already running. */
    class Shared(val summary: RefreshSummary, val joined: Boolean)

    // Held only to read or swap [running], never across a suspension: a joiner resumed at once by the refresh it joined
    // (as Dispatchers.Main.immediate does, on the same thread) must never find the lock, or that refresh, still held.
    private val lock = Any()
    private var running: CompletableDeferred<RefreshSummary>? = null
    private val foreground = MutableStateFlow(0)

    /** True while no refresh runs and no foreground work is in progress; background filling waits for it. */
    val backgroundTurns: Flow<Boolean> = foreground.map { it == 0 }.distinctUntilChanged()

    /** Runs [block] ahead of background filling. */
    suspend fun <T> foreground(block: suspend () -> T): T {
        foreground.update { it + 1 }
        try { return block() } finally { foreground.update { it - 1 } }
    }

    /**
     * Runs [refresh], or joins the refresh already running and returns its result. When the refresh it joined was
     * cancelled, as when the system stops background work or the screen replaces its refresh, this caller runs its own.
     */
    suspend fun shared(refresh: suspend () -> RefreshSummary): Shared {
        while (true) {
            val mine = CompletableDeferred<RefreshSummary>()
            val other = synchronized(lock) { running.also { if (it == null) running = mine } }
            if (other == null) return Shared(run(mine, refresh), joined = false)
            await(other)?.let { return Shared(it, joined = true) }
        }
    }

    /** The result of the refresh running now, joined; null when none runs or it was cancelled. */
    suspend fun joinRunning(): RefreshSummary? = synchronized(lock) { running }?.let { await(it) }

    /** [refresh]'s result; null when it was cancelled while this caller is still active. Its failure is this caller's. */
    private suspend fun await(refresh: CompletableDeferred<RefreshSummary>): RefreshSummary? {
        val outcome = runCatching { refresh.await() }
        val failure = outcome.exceptionOrNull() ?: return outcome.getOrThrow()
        if (failure !is CancellationException) throw failure
        currentCoroutineContext().ensureActive()
        return null
    }

    private suspend fun run(mine: CompletableDeferred<RefreshSummary>, refresh: suspend () -> RefreshSummary): RefreshSummary {
        // Whichever way it ends, cancellation included, the refresh is completed so callers that joined it never wait
        // on it forever. It stops being the running one first: a joiner can resume inside completeWith, before this
        // returns, and one that found a cancelled refresh still running would loop on it without ever suspending.
        val outcome = runCatching { foreground(refresh) }
        synchronized(lock) { if (running === mine) running = null }
        mine.completeWith(outcome)
        return outcome.getOrThrow()
    }
}
