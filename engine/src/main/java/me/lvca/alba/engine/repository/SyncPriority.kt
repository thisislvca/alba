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
 * Who goes first in the catalog. A library refresh runs once at a time. A refresh asked for while one runs must see
 * what changed before it was asked, which the running one may have read past: it waits for one follow-up refresh that
 * starts once the running one ends, and every caller that asked meanwhile shares that follow-up, so many requests
 * during a long read still cost a single extra read. While a refresh runs, or a thumbnail or full-size image is wanted
 * on screen, background thumbnail filling waits between downloads.
 */
internal class SyncPriority {
    /** A refresh's result, and whether this caller shared a refresh another caller ran. */
    class Shared(val summary: RefreshSummary, val joined: Boolean)

    /** One refresh, running or waiting to follow the running one. */
    private class Round(var includeDeviceMedia: Boolean) {
        val result = CompletableDeferred<RefreshSummary>()

        /** Over, and no longer running; its result is about to be, or has been, completed. */
        var ended = false

        /** Once this round was cancelled, the round its callers share instead. */
        var replacement: Round? = null
    }

    /** What a caller does next; [round] is the refresh it waits for. */
    private sealed class Step(val round: Round) {
        class Run(round: Round) : Step(round)
        class Await(round: Round) : Step(round)
        class WaitForEnd(val running: Round, followUp: Round) : Step(followUp)
    }

    // Held only to read or change the rounds, never across a suspension: a waiter resumed at once by the refresh it
    // waited for (as Dispatchers.Main.immediate does, on the same thread) must never find the lock, or that refresh,
    // still held.
    private val lock = Any()
    private var running: Round? = null
    private var followUp: Round? = null
    private val foreground = MutableStateFlow(0)

    /** True while no refresh runs and no foreground work is in progress; background filling waits for it. */
    val backgroundTurns: Flow<Boolean> = foreground.map { it == 0 }.distinctUntilChanged()

    /** Runs [block] ahead of background filling. */
    suspend fun <T> foreground(block: suspend () -> T): T {
        foreground.update { it + 1 }
        try { return block() } finally { foreground.update { it - 1 } }
    }

    /**
     * Runs a refresh that starts after this call, alone or shared. With none running, [refresh] runs at once. With one
     * running, this caller waits for the follow-up that starts when it ends, reading the phone too if any of its
     * callers asked to. When a refresh this caller waits on is cancelled, as when the system stops background work or
     * the screen replaces its refresh, this caller carries on with one of its own rather than waiting forever.
     */
    suspend fun shared(includeDeviceMedia: Boolean, refresh: suspend (includeDeviceMedia: Boolean) -> RefreshSummary): Shared {
        var mine: Round? = null
        var cancelled: Round? = null
        while (true) {
            val step = synchronized(lock) { next(mine ?: cancelled?.let(::replacementOf), includeDeviceMedia) }
            mine = step.round
            when (step) {
                is Step.Run -> return Shared(run(step.round, refresh), joined = false)
                is Step.Await -> {
                    await(step.round.result)?.let { return Shared(it, joined = true) }
                    // Its callers share one replacement, which the first of them to get here asks for.
                    cancelled = step.round
                    mine = null
                }
                is Step.WaitForEnd -> waitForEnd(step.running)
            }
        }
    }

    /** Waits for the refresh running now, if any, to end, however it ends. It asks for no refresh itself. */
    suspend fun awaitRunning() {
        synchronized(lock) { running }?.let { waitForEnd(it) }
    }

    /**
     * The round that replaces [cancelled] for its callers: the one the first of them asked for, or a new one this caller
     * asks for. Either started after every one of them asked. Called under [lock].
     */
    private fun replacementOf(cancelled: Round): Round {
        cancelled.replacement?.let { return it }
        val round = followUp ?: Round(includeDeviceMedia = false).also { if (running != null) followUp = it }
        cancelled.replacement = round
        return round
    }

    /** What a caller waiting on [mine] (null before it asked) does next. Called under [lock]. */
    private fun next(mine: Round?, includeDeviceMedia: Boolean): Step {
        if (mine != null && (mine.ended || mine === running)) return Step.Await(mine)
        mine?.let { it.includeDeviceMedia = it.includeDeviceMedia || includeDeviceMedia }
        val current = running
        if (current != null) {
            // Asked for after the running refresh started: wait for the one follow-up every such caller shares.
            val round = mine ?: (followUp ?: Round(includeDeviceMedia = false).also { followUp = it }).also {
                it.includeDeviceMedia = it.includeDeviceMedia || includeDeviceMedia
            }
            return Step.WaitForEnd(current, round)
        }
        // Nothing runs: start the round this caller waits for, the follow-up others wait for, or a new refresh.
        val round = (mine ?: followUp ?: Round(includeDeviceMedia)).also {
            it.includeDeviceMedia = it.includeDeviceMedia || includeDeviceMedia
        }
        if (followUp === round) followUp = null
        running = round
        return Step.Run(round)
    }

    private suspend fun run(round: Round, refresh: suspend (Boolean) -> RefreshSummary): RefreshSummary {
        // Whichever way it ends, cancellation included, the round is completed so callers waiting on it never wait
        // forever. It stops running first: a waiter can resume inside completeWith, before this returns, and must find
        // it over, never still running, or it would loop on it without suspending.
        val outcome = runCatching { foreground { refresh(round.includeDeviceMedia) } }
        synchronized(lock) {
            round.ended = true
            if (running === round) running = null
        }
        round.result.completeWith(outcome)
        return outcome.getOrThrow()
    }

    /** [result]; null when it was cancelled while this caller is still active. Its failure is this caller's. */
    private suspend fun await(result: CompletableDeferred<RefreshSummary>): RefreshSummary? {
        val outcome = runCatching { result.await() }
        val failure = outcome.exceptionOrNull() ?: return outcome.getOrThrow()
        if (failure !is CancellationException) throw failure
        currentCoroutineContext().ensureActive()
        return null
    }

    /** Waits for [round] to end, whatever its outcome: only this caller's own cancellation ends the wait early. */
    private suspend fun waitForEnd(round: Round) {
        runCatching { round.result.await() }
        currentCoroutineContext().ensureActive()
    }
}
