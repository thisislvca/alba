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
import me.lvca.alba.app.diagnostics.AlbaDiagnostics
import org.junit.Assert.assertEquals
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
            // Four permits: only the first four visible photos start.
            withTimeout(5_000) { gallery.started.first { it.size == 4 } }
            queue.prefetch(last)
            gallery.release.complete(Unit)
            withTimeout(10_000) { gallery.previews.first { it.containsAll(last.take(44)) } }
            assertEquals(48, gallery.started.value.size)
            // The fling retired queued look-ahead but let the in-flight downloads finish.
            assertEquals(first.take(4) + last.take(44), gallery.started.value)
            assertTrue(gallery.previews.value.containsAll(first.take(4)))
            // Scrolling back over retained thumbnails does not download them again.
            queue.prefetch(last)
            withTimeout(10_000) { gallery.previews.first { it.containsAll(last) } }
            assertEquals(52, gallery.started.value.size)
        } finally {
            gallery.release.complete(Unit)
            scope.cancel()
        }
    }
}
