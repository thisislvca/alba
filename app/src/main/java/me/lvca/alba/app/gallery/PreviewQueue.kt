package me.lvca.alba.app.gallery

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.lvca.alba.app.diagnostics.AlbaDiagnostics
import me.lvca.alba.app.diagnostics.DiagnosticOperation
import me.lvca.alba.app.diagnostics.DiagnosticOutcome
import me.lvca.alba.app.diagnostics.DiagnosticSpan
import me.lvca.alba.engine.cache.InsufficientStorageException

/**
 * Main-thread confined queue: keeps visible work bounded and retires obsolete look-ahead.
 *
 * Waiting previews start in priority order, not arrival order: the photo open in the viewer first, then the grid's
 * latest window as [prefetch] ranks it (visible tiles, then the rows ahead, then those behind), then anything else
 * asked for directly, oldest first. So after a scroll the newly visible tiles overtake look-ahead queued earlier.
 *
 * A failed download gives its slot back at once, waits out its retry delay holding none, then queues again at
 * whatever priority it has by then, so a flaky photo never stalls the tiles behind it.
 */
internal class PreviewQueue(
    private val scope: CoroutineScope,
    private val selectedMediaId: () -> String?,
    private val needsPreview: (String) -> Boolean,
    private val load: suspend (String) -> Unit,
    private val diagnostics: AlbaDiagnostics,
    private val onFailure: (Throwable) -> Unit,
    /** How long to wait before each new attempt after a failed download, so a passing failure never leaves a blank tile. */
    private val retryDelaysMillis: List<Long> = listOf(FIRST_RETRY_MILLIS, SECOND_RETRY_MILLIS),
) {
    /** Downloads under way, by media id. */
    private val previewJobs = linkedMapOf<String, Job>()

    /** Previews asked for but not started, in arrival order. */
    private val waiting = linkedSetOf<String>()

    /** Previews waiting out a retry delay after a failed attempt; they hold no download slot. */
    private val retryTimers = linkedMapOf<String, Job>()

    /** Failed attempts so far for each preview still being tried. */
    private val failedAttempts = mutableMapOf<String, Int>()

    /** One diagnostics span per preview, from its first attempt to its outcome, retries included. */
    private val spans = mutableMapOf<String, DiagnosticSpan>()

    /** Each id's place in the grid's latest prefetch window; lower starts sooner. */
    private var window: Map<String, Int> = emptyMap()

    /** Downloads still running, including cancelled ones that have not finished unwinding. */
    private var running = 0
    private val blocked = MutableStateFlow(false)

    /** Whether a preview write failed for lack of space; tiles explain that instead of a network failure. */
    val storageBlockedState: StateFlow<Boolean> = blocked.asStateFlow()
    var storageBlocked: Boolean
        get() = blocked.value
        set(value) {
            blocked.value = value
            if (!value) startWaiting()
        }

    fun cancel(): List<Job> = (previewJobs.values + retryTimers.values).toList().also { jobs ->
        waiting.toList().forEach { id -> settle(id, DiagnosticOutcome.CANCELLED) }
        waiting.clear()
        previewJobs.clear()
        retryTimers.clear()
        jobs.forEach(Job::cancel)
    }

    suspend fun cancelAndJoin() { cancel().joinAll() }

    fun request(mediaId: String) {
        if (storageBlocked) return
        if (!needsPreview(mediaId)) return
        if (previewJobs[mediaId]?.isActive == true || mediaId in waiting || mediaId in retryTimers) return
        if (previewJobs.size + waiting.size >= MAX_PENDING_PREVIEWS) {
            // Make room by dropping the waiting preview that would start last, the newest of equals; a full queue of
            // downloads already under way turns the request away.
            val last = waiting.toList().asReversed().maxByOrNull(::priority) ?: return
            drop(last)
        }
        waiting += mediaId
        startWaiting()
    }

    fun prefetch(mediaIds: List<String>) {
        val candidates = mediaIds.distinct().take(MAX_PENDING_PREVIEWS)
        val finishing = previewJobs.keys.count { id -> id !in candidates }
        // Reserve room for retained in-flight work, trimming look-ahead from the end
        // instead of dropping the first (currently visible) photos in a full window.
        val wanted = candidates.take((MAX_PENDING_PREVIEWS - finishing).coerceAtLeast(0))
        window = wanted.withIndex().associate { (index, id) -> id to index }
        // Replace obsolete queued work after a fling. Let in-flight bytes finish so
        // briefly browsing past a photo still leaves a useful thumbnail on disk.
        waiting.filter { id -> id !in window && id != selectedMediaId() }.forEach(::drop)
        wanted.forEach(::request)
    }

    private fun priority(mediaId: String): Int = when (mediaId) {
        selectedMediaId() -> -1
        else -> window[mediaId] ?: MAX_PENDING_PREVIEWS
    }

    /** Starts the most urgent waiting previews while there is a free download slot. */
    private fun startWaiting() {
        while (running < MAX_CONCURRENT_PREVIEWS && !storageBlocked) {
            // minBy keeps the first of equals, so direct requests outside the window go oldest first.
            val next = waiting.minByOrNull(::priority) ?: return
            waiting.remove(next)
            start(next)
        }
    }

    private fun start(mediaId: String) {
        spans.getOrPut(mediaId) { diagnostics.start(DiagnosticOperation.PREVIEW_DOWNLOAD) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                load(mediaId)
                settle(mediaId, DiagnosticOutcome.SUCCESS)
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    settle(mediaId, DiagnosticOutcome.CANCELLED)
                    throw error
                }
                failed(mediaId, error)
            }
        }
        // Runs however the job ends, even if it was cancelled before its body ran, so the slot is always returned.
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) settle(mediaId, DiagnosticOutcome.CANCELLED)
            running -= 1
            if (previewJobs[mediaId] === job) previewJobs.remove(mediaId)
            startWaiting()
        }
        running += 1
        previewJobs[mediaId] = job
        job.start()
    }

    /**
     * A tile asks for its preview only when it comes into view, so a passing failure is tried again before the tile
     * is left blank. Running out of space is not passing: it pauses every preview at once.
     */
    private fun failed(mediaId: String, error: Throwable) {
        if (error is InsufficientStorageException) storageBlocked = true
        val attempt = failedAttempts[mediaId] ?: 0
        val wait = retryDelaysMillis.getOrNull(attempt)
        if (wait == null || storageBlocked) {
            settle(mediaId, DiagnosticOutcome.FAILED, error)
            if (selectedMediaId() == mediaId || storageBlocked) onFailure(error)
            return
        }
        failedAttempts[mediaId] = attempt + 1
        val wasInWindow = mediaId in window
        val timer = scope.launch(start = CoroutineStart.LAZY) {
            delay(wait)
            retryTimers.remove(mediaId)
            when {
                !needsPreview(mediaId) -> settle(mediaId, DiagnosticOutcome.SUCCESS)
                // Scrolled away while it waited: let it go, like any other look-ahead that left the window.
                wasInWindow && mediaId !in window && mediaId != selectedMediaId() -> settle(mediaId, DiagnosticOutcome.CANCELLED)
                else -> {
                    request(mediaId)
                    if (mediaId !in waiting && mediaId !in previewJobs) settle(mediaId, DiagnosticOutcome.CANCELLED)
                }
            }
        }
        timer.invokeOnCompletion { cause ->
            if (cause is CancellationException) settle(mediaId, DiagnosticOutcome.CANCELLED)
            if (retryTimers[mediaId] === timer) retryTimers.remove(mediaId)
        }
        retryTimers[mediaId] = timer
        timer.start()
    }

    /** Takes a preview that has not started out of the queue. */
    private fun drop(mediaId: String) {
        if (waiting.remove(mediaId)) settle(mediaId, DiagnosticOutcome.CANCELLED)
    }

    /** Ends a preview's diagnostics span, if it was ever started, and forgets its failed attempts. */
    private fun settle(mediaId: String, outcome: DiagnosticOutcome, error: Throwable? = null) {
        failedAttempts.remove(mediaId)
        spans.remove(mediaId)?.finish(outcome, error = error)
    }

    private companion object {
        const val MAX_PENDING_PREVIEWS = 48
        const val FIRST_RETRY_MILLIS = 1_000L
        const val SECOND_RETRY_MILLIS = 4_000L

        /**
         * Previews downloading at once. A grid preview is one small resJPEGThumb from Apple's CDN
         * (cvws.icloud-content.com), which speaks HTTP/2, so OkHttp multiplexes these as streams on one connection
         * instead of opening one each. Fetching a screenful is bound by round trips, not bandwidth: 48 requests to
         * that host from a home connection took about 0.85 s four at a time and 0.45 s eight at a time (curl,
         * HTTP/2, three runs each), and twelve gained little more. Eight halves the wait for a screenful and stays a
         * modest load on Apple's CDN. OkHttpAppleTransport raises OkHttp's per-host limit for async calls (5 by
         * default, which would silently cap this) so grid previews never starve the viewer's own downloads.
         */
        const val MAX_CONCURRENT_PREVIEWS = 8
    }
}
