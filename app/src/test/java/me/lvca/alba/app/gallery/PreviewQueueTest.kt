package me.lvca.alba.app.gallery

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.lvca.alba.app.diagnostics.AlbaDiagnostics
import me.lvca.alba.engine.cache.InsufficientStorageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewQueueTest {
    /** A gallery of 120 photos without previews; a load records its start, waits for [release], then stores the preview. */
    private class Gallery {
        val ids = List(120) { index -> "fixture:queue:$index" }
        val previews = MutableStateFlow<Set<String>>(emptySet())
        val started = MutableStateFlow<List<String>>(emptyList())
        val release = CompletableDeferred<Unit>()

        suspend fun load(id: String) {
            started.update { it + id }
            release.await()
            previews.update { it + id }
        }
    }

    // runBlocking's single-threaded event loop stands in for the main thread the queue is confined to.
    @Test fun fastScrollReplacesObsoleteQueueAndKeepsInflightThumbnails() = runBlocking {
        val gallery = Gallery()
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
        val queue = PreviewQueue(
            scope = scope,
            selectedMediaId = { null },
            needsPreview = { id -> id !in gallery.previews.value },
            load = gallery::load,
            diagnostics = AlbaDiagnostics.None,
            onFailure = {},
        )
        val first = gallery.ids.take(48)
        val last = gallery.ids.takeLast(48)
        try {
            queue.prefetch(first)
            // Eight download slots: only the first eight visible photos start.
            withTimeout(5_000) { gallery.started.first { it.size == 8 } }
            yield()
            assertEquals(first.take(8), gallery.started.value)
            queue.prefetch(last)
            gallery.release.complete(Unit)
            withTimeout(10_000) { gallery.previews.first { it.containsAll(last.take(40)) } }
            assertEquals(48, gallery.started.value.size)
            // The fling retired queued look-ahead but let the in-flight downloads finish.
            assertEquals(first.take(8) + last.take(40), gallery.started.value)
            assertTrue(gallery.previews.value.containsAll(first.take(8)))
            // Scrolling back over retained thumbnails does not download them again.
            queue.prefetch(last)
            withTimeout(10_000) { gallery.previews.first { it.containsAll(last) } }
            assertEquals(56, gallery.started.value.size)
        } finally {
            gallery.release.complete(Unit)
            scope.cancel()
        }
    }

    /**
     * A short scroll keeps earlier look-ahead in the window, now as rows behind. The newly visible tiles start before
     * it although it was queued first, and the photo open in the viewer starts before either.
     */
    @Test fun visibleTilesOvertakeLookAheadQueuedEarlier() = runBlocking {
        val gallery = Gallery()
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
        var selected: String? = null
        val queue = PreviewQueue(
            scope = scope,
            selectedMediaId = { selected },
            needsPreview = { id -> id !in gallery.previews.value },
            load = gallery::load,
            diagnostics = AlbaDiagnostics.None,
            onFailure = {},
        )
        val ids = gallery.ids
        try {
            // Tiles 0-11 on screen, 12-19 ahead: 0-7 start, the rest wait in that order.
            queue.prefetch(ids.subList(0, 20))
            withTimeout(5_000) { gallery.started.first { it.size == 8 } }
            // Scrolled down by 12: 12-23 on screen, 24-31 ahead, 11 back to 4 behind. 8-11 were queued before 12-31.
            queue.prefetch(ids.subList(12, 32) + ids.subList(4, 12).asReversed())
            selected = ids[100]
            queue.request(ids[100])
            gallery.release.complete(Unit)
            withTimeout(10_000) { gallery.started.first { it.size == 33 } }
            assertEquals(ids.take(8) + ids[100] + ids.subList(12, 32) + ids.subList(8, 12).asReversed(), gallery.started.value)
        } finally {
            gallery.release.complete(Unit)
            scope.cancel()
        }
    }

    @Test fun lowStorageIsObservableAndPausesPreviewsUntilCleared() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
        val loads = MutableStateFlow(0)
        val queue = PreviewQueue(
            scope = scope,
            selectedMediaId = { null },
            needsPreview = { true },
            load = { loads.update { it + 1 }; throw InsufficientStorageException() },
            diagnostics = AlbaDiagnostics.None,
            onFailure = {},
        )
        try {
            queue.request("fixture:queue:full")
            withTimeout(5_000) { queue.storageBlockedState.first { it } }
            queue.request("fixture:queue:next")
            assertEquals(1, loads.value)
            queue.storageBlocked = false
            assertFalse(queue.storageBlockedState.value)
        } finally {
            scope.cancel()
        }
    }
}
