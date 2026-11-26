package me.lvca.alba.engine.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.lvca.alba.engine.cache.InsufficientStorageException
import me.lvca.alba.engine.model.OfflineThumbnails
import me.lvca.alba.engine.source.ThrottledFailure

/** What a failed thumbnail download means for the rest of the run. */
enum class FillFailure {
    /** Only this photo: it is tried again, and skipped for the run after [OfflineThumbnailFill.MAX_ATTEMPTS]. */
    ITEM,

    /** Apple asked Alba to slow down. */
    THROTTLED,

    /** The sign-in expired. */
    SIGNED_OUT,

    /** The phone is out of room. */
    STORAGE,

    /** The connection dropped. */
    CONNECTION,
}

/** How a run of [OfflineThumbnailFill] ended. */
sealed interface FillOutcome {
    /** Every thumbnail of the offline set is saved, except [skipped] photos that kept failing this run. */
    data class Finished(val skipped: Int) : FillOutcome

    /** [OfflineThumbnailFill.stop] was called, as when the budget shrank; whoever stopped it starts it again. */
    data object Stopped : FillOutcome

    /** Free space came near the low-storage reserve: the run stopped before eating into it. */
    data object StorageLow : FillOutcome

    /** Apple asked Alba to slow down, for [retryAfterMillis] when it said. */
    data class Throttled(val retryAfterMillis: Long?) : FillOutcome

    /** The sign-in expired: nothing is asked of the user, and signing in again starts the fill again. */
    data object SignedOut : FillOutcome

    /** The connection dropped, or [keepGoing] said to stop. */
    data object Interrupted : FillOutcome
}

/** Throttling and full storage, found anywhere in the cause chain; anything else concerns only its photo. */
fun fillFailure(error: Throwable): FillFailure {
    val chain = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
    return when {
        chain.any { it is ThrottledFailure } -> FillFailure.THROTTLED
        chain.any { it is InsufficientStorageException } -> FillFailure.STORAGE
        else -> FillFailure.ITEM
    }
}

private const val MAX_CAUSE_DEPTH = 8

/**
 * Saves the offline set's missing thumbnails, newest photo first, a batch at a time. What is left is read from the
 * catalog before each batch, and each saved thumbnail is recorded there as it lands, so a run that is interrupted
 * loses at most the downloads in flight and the next one carries on where it stopped.
 *
 * It is gentle with Apple: [concurrency] downloads at a time, a [batchPause] between batches, and the first sign of
 * throttling ends the run, so the scheduler can back off. Before each batch it checks there is room for the batch
 * above the low-storage reserve, so it never fills the phone into the trim that would remove what it saved.
 */
class OfflineThumbnailFill(
    private val thumbnails: OfflineThumbnails,
    private val classify: (Throwable) -> FillFailure = ::fillFailure,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val batchSize: Int = BATCH_SIZE,
    private val concurrency: Int = CONCURRENCY,
    private val batchPause: Long = BATCH_PAUSE_MILLIS,
    /** Room a batch needs above the reserve before it starts. */
    private val batchRoom: Long = BATCH_ROOM_BYTES,
) {
    private val running = Mutex()

    // A state flow, so a fill waiting for its turn also wakes when it is stopped.
    private val generation = MutableStateFlow(0)

    /** Runs until the set is complete or something stops it; [keepGoing] is checked before each download. */
    suspend fun run(keepGoing: () -> Boolean = { true }): FillOutcome = running.withLock { fill(generation.value, keepGoing) }

    private suspend fun fill(started: Int, keepGoing: () -> Boolean): FillOutcome {
        val go = { keepGoing() && generation.value == started }
        val attempts = HashMap<String, Int>()
        val skipped = LinkedHashSet<String>()
        var outcome: FillOutcome? = null
        var first = true
        while (outcome == null) {
            outcome = when {
                !go() -> FillOutcome.Interrupted
                thumbnails.offlineThumbnailRoom() < batchRoom -> FillOutcome.StorageLow
                else -> fillBatch(thumbnails.missingOfflineThumbnails(batchSize, skipped), first, go, attempts, skipped)
            }
            first = false
            // A stop shows as the downloads ending early; say why.
            if ((outcome == null || outcome == FillOutcome.Interrupted) && generation.value != started) outcome = FillOutcome.Stopped
        }
        return outcome
    }

    /** One batch, after a pause unless it is the run's first: null to carry on with the next. */
    private suspend fun fillBatch(batch: List<String>, first: Boolean, go: () -> Boolean, attempts: MutableMap<String, Int>,
        skipped: MutableSet<String>): FillOutcome? {
        if (batch.isEmpty()) return FillOutcome.Finished(skipped.size)
        if (!first) pause(batchPause)
        return prepare(batch) ?: downloadBatch(batch, go, attempts, skipped)
            ?: FillOutcome.Finished(skipped.size).takeIf { skipped.size >= MAX_SKIPPED }
    }

    /** Stops a run in progress after its downloads in flight, and waits for it to end. */
    suspend fun stopAndJoin() {
        generation.update { it + 1 }
        running.withLock { }
    }

    /** Renews the batch's links in one request; a failure that concerns single photos is left to their downloads. */
    private suspend fun prepare(batch: List<String>): FillOutcome? =
        failureOf { thumbnails.prepareDownloads(batch) }?.let { error ->
            classify(error).takeUnless { it == FillFailure.ITEM }?.let { outcome(it, error) }
        }

    /** What [block] failed with, if anything; cancellation is never a failure and goes on up. */
    private suspend fun failureOf(block: suspend () -> Unit): Throwable? =
        runCatching { block() }.exceptionOrNull()?.also { if (it is CancellationException || it is Error) throw it }

    private suspend fun downloadBatch(
        batch: List<String>,
        go: () -> Boolean,
        attempts: MutableMap<String, Int>,
        skipped: MutableSet<String>,
    ): FillOutcome? {
        val queue = ArrayDeque(batch)
        val lock = Any()
        var stop: FillOutcome? = null
        // The fill's turn, or its stop: whichever comes first ends a wait, with no polling in between.
        val turnOrStop = combine(thumbnails.backgroundTurns(), generation) { turn, _ -> turn || !go() }
        coroutineScope {
            repeat(minOf(concurrency, batch.size)) {
                launch {
                    while (true) {
                        // A refresh or what is on screen goes first: wait for it between downloads, never during one.
                        turnOrStop.first { it }
                        val id = synchronized(lock) {
                            if (stop != null) return@synchronized null
                            if (!go()) { stop = FillOutcome.Interrupted; return@synchronized null }
                            queue.removeFirstOrNull()?.also { id ->
                                // Counted per try, so a photo that saves but never shows as saved cannot loop either.
                                val tries = (attempts[id] ?: 0) + 1
                                attempts[id] = tries
                                if (tries >= MAX_ATTEMPTS) skipped += id
                            }
                        } ?: break
                        failureOf { thumbnails.saveOfflineThumbnail(id) }?.let { error ->
                            val kind = classify(error)
                            if (kind != FillFailure.ITEM) synchronized(lock) { if (stop == null) stop = outcome(kind, error) }
                        }
                    }
                }
            }
        }
        return stop
    }

    private fun outcome(kind: FillFailure, error: Throwable): FillOutcome = when (kind) {
        FillFailure.THROTTLED -> FillOutcome.Throttled(
            generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).filterIsInstance<ThrottledFailure>().firstOrNull()?.retryAfterMillis,
        )
        FillFailure.SIGNED_OUT -> FillOutcome.SignedOut
        FillFailure.STORAGE -> FillOutcome.StorageLow
        FillFailure.CONNECTION, FillFailure.ITEM -> FillOutcome.Interrupted
    }

    companion object {
        /** Thumbnails per batch: about 2.5 MB, read from the catalog with one query and renewed with one lookup. */
        const val BATCH_SIZE = 40

        /** Downloads at a time: the same two the repository allows the grid, so neither waits long on the other. */
        const val CONCURRENCY = 2

        /** The pause between batches, so a long fill never holds a steady stream of requests on Apple. */
        const val BATCH_PAUSE_MILLIS = 2_000L

        /** Free space a batch needs above the low-storage reserve: well over what [BATCH_SIZE] thumbnails take. */
        const val BATCH_ROOM_BYTES = 64L * 1_024 * 1_024

        /** Tries a photo gets in one run before it is skipped until the next run. */
        const val MAX_ATTEMPTS = 3

        /** Skipped photos after which a run ends: something is wrong beyond single photos. */
        const val MAX_SKIPPED = 200
    }
}
