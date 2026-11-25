package me.lvca.alba.app.gallery

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.lvca.alba.app.diagnostics.AlbaDiagnostics
import me.lvca.alba.app.diagnostics.DiagnosticOperation
import me.lvca.alba.app.diagnostics.DiagnosticOutcome
import me.lvca.alba.engine.cache.InsufficientStorageException

/**
 * Main-thread confined queue: keeps visible work bounded and retires obsolete look-ahead.
 *
 * Waiting previews start in priority order, not arrival order: the photo open in the viewer first, then the grid's
 * latest window as [prefetch] ranks it (visible tiles, then the rows ahead, then those behind), then anything else
 * asked for directly, oldest first. So after a scroll the newly visible tiles overtake look-ahead queued earlier.
 */
internal class PreviewQueue(
    private val scope: CoroutineScope,
    private val selectedMediaId: () -> String?,
    private val needsPreview: (String) -> Boolean,
    private val load: suspend (String) -> Unit,
    private val diagnostics: AlbaDiagnostics,
    private val onFailure: (Throwable) -> Unit,
) {
    /** Downloads under way, by media id. */
    private val previewJobs = linkedMapOf<String, Job>()

    /** Previews asked for but not started, in arrival order. */
    private val waiting = linkedSetOf<String>()

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

    fun cancel(): List<Job> = previewJobs.values.toList().also { jobs ->
        waiting.clear()
        previewJobs.clear()
        jobs.forEach(Job::cancel)
    }

    suspend fun cancelAndJoin() { cancel().joinAll() }

    fun request(mediaId: String) {
        if (storageBlocked) return
        if (!needsPreview(mediaId)) return
        if (previewJobs[mediaId]?.isActive == true || mediaId in waiting) return
        if (previewJobs.size + waiting.size >= MAX_PENDING_PREVIEWS) {
            // Make room by dropping the waiting preview that would start last, the newest of equals; a full queue of
            // downloads already under way turns the request away.
            val last = waiting.toList().asReversed().maxByOrNull(::priority) ?: return
            waiting.remove(last)
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
        waiting.retainAll { id -> id in window || id == selectedMediaId() }
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
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val span = diagnostics.start(DiagnosticOperation.PREVIEW_DOWNLOAD)
            try {
                load(mediaId)
                span.finish(DiagnosticOutcome.SUCCESS)
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    span.finish(DiagnosticOutcome.CANCELLED)
                    throw error
                }
                span.finish(DiagnosticOutcome.FAILED, error = error)
                if (error is InsufficientStorageException) storageBlocked = true
                if (selectedMediaId() == mediaId || storageBlocked) {
                    onFailure(error)
                }
            }
        }
        // Runs however the job ends, even if it was cancelled before its body ran, so the slot is always returned.
        job.invokeOnCompletion {
            running -= 1
            if (previewJobs[mediaId] === job) previewJobs.remove(mediaId)
            startWaiting()
        }
        running += 1
        previewJobs[mediaId] = job
        job.start()
    }

    private companion object {
        const val MAX_PENDING_PREVIEWS = 48

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
