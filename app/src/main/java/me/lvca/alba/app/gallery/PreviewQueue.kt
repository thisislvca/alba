package me.lvca.alba.app.gallery

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.lvca.alba.app.diagnostics.AlbaDiagnostics
import me.lvca.alba.app.diagnostics.DiagnosticOperation
import me.lvca.alba.app.diagnostics.DiagnosticOutcome
import me.lvca.alba.engine.cache.InsufficientStorageException

/** Main-thread confined queue: keeps visible work bounded and retires obsolete look-ahead. */
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
    private val previewJobs = linkedMapOf<String, Job>()
    private val runningPreviews = mutableSetOf<String>()
    private val blocked = MutableStateFlow(false)

    /** Whether a preview write failed for lack of space; tiles explain that instead of a network failure. */
    val storageBlockedState: StateFlow<Boolean> = blocked.asStateFlow()
    var storageBlocked: Boolean
        get() = blocked.value
        set(value) { blocked.value = value }
    private val previewPermits = Semaphore(4)

    fun cancel(): List<Job> = previewJobs.values.toList().also { jobs ->
        previewJobs.clear()
        jobs.forEach(Job::cancel)
    }

    suspend fun cancelAndJoin() { cancel().joinAll() }

    fun request(mediaId: String) {
        if (storageBlocked) return
        if (!needsPreview(mediaId)) return
        queuePreview(mediaId)
    }

    fun prefetch(mediaIds: List<String>) {
        val candidates = mediaIds.distinct().take(MAX_PENDING_PREVIEWS)
        val finishing = previewJobs.keys.count { id ->
            id !in candidates && (id in runningPreviews || id == selectedMediaId())
        }
        // Reserve room for retained in-flight work, trimming look-ahead from the end
        // instead of dropping the first (currently visible) photos in a full window.
        val wanted = candidates.take((MAX_PENDING_PREVIEWS - finishing).coerceAtLeast(0)).toSet()
        // Replace obsolete queued work after a fling. Let in-flight bytes finish so
        // briefly browsing past a photo still leaves a useful thumbnail on disk.
        previewJobs.entries.toList().forEach { (id, job) ->
            if (id !in wanted && id != selectedMediaId() && id !in runningPreviews) {
                previewJobs.remove(id)
                job.cancel()
            }
        }
        wanted.forEach(::request)
    }

    private fun queuePreview(mediaId: String) {
        if (previewJobs[mediaId]?.isActive == true) return
        if (previewJobs.size >= MAX_PENDING_PREVIEWS) {
            val oldestBackground = previewJobs.entries.lastOrNull { it.key != selectedMediaId() && it.key !in runningPreviews }
                ?: return
            previewJobs.remove(oldestBackground.key)
            oldestBackground.value.cancel()
        }

        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            val span = diagnostics.start(DiagnosticOperation.PREVIEW_DOWNLOAD)
            try {
                loadWithRetries(mediaId)
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
            } finally {
                if (previewJobs[mediaId] === job) previewJobs.remove(mediaId)
            }
        }
        previewJobs[mediaId] = job
        job.start()
    }

    /**
     * A tile asks for its preview only when it comes into view, so a passing failure is tried again here, outside the
     * permit, before the tile is left blank. Running out of space is not passing: it pauses every preview at once.
     */
    private suspend fun loadWithRetries(mediaId: String) {
        for (wait in retryDelaysMillis + null) {
            val failure = runCatching { loadOnce(mediaId) }.exceptionOrNull() ?: return
            if (failure is CancellationException || failure is InsufficientStorageException || wait == null) throw failure
            delay(wait)
            if (!needsPreview(mediaId)) return
        }
    }

    private suspend fun loadOnce(mediaId: String) = previewPermits.withPermit {
        if (storageBlocked) return@withPermit
        runningPreviews += mediaId
        try { load(mediaId) }
        finally { runningPreviews -= mediaId }
    }

    private companion object {
        const val MAX_PENDING_PREVIEWS = 48
        const val FIRST_RETRY_MILLIS = 1_000L
        const val SECOND_RETRY_MILLIS = 4_000L
    }
}
