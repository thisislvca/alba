package me.lvca.alba.protocol.status

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.lvca.alba.engine.model.ProtocolArea
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

/** Fetching, caching and failing open, on a fake clock and network. */
class RemoteProtocolStatusTest {
    private var clock = 1_000_000_000L
    private var document: String? = CLEAR
    private var fetches = 0
    private val memory = object : ProtocolStatusCache {
        var saved: CachedProtocolStatus? = null
        override fun read() = saved
        override fun write(status: CachedProtocolStatus) { saved = status }
    }

    private fun status(version: String = "0.4.0", cache: ProtocolStatusCache = memory) = RemoteProtocolStatus(
        fetch = { fetches++; document ?: throw IOException("unreachable") },
        cache = cache,
        appVersionName = version,
        now = { clock },
    )

    @Test
    fun anUnreachableStatusPausesNothing() = runBlocking {
        document = null
        val status = status()

        assertNull(status.pauseFor(ProtocolArea.UPLOADS))
        assertTrue(status.pauses.first().isEmpty())
        assertEquals(1, fetches)
    }

    @Test
    fun aPauseCoversOnlyItsAreas() = runBlocking {
        document = paused("uploads")
        val status = status()

        assertEquals("pause", status.pauseFor(ProtocolArea.UPLOADS)?.id)
        assertNull(status.pauseFor(ProtocolArea.LIBRARY_EDITS))
        assertNull(status.pauseFor(ProtocolArea.SHARED_ALBUMS))
        assertEquals(listOf("pause"), status.pauses.first().map { it.id })
    }

    @Test
    fun aFreshStatusIsNotFetchedAgain() = runBlocking {
        val status = status()
        status.pauseFor(ProtocolArea.UPLOADS)
        clock += RemoteProtocolStatus.CLEAR_TTL_MILLIS - 1
        status.pauseFor(ProtocolArea.UPLOADS)
        status.pauseFor(ProtocolArea.LIBRARY_EDITS)
        assertEquals(1, fetches)

        clock += 1
        status.pauseFor(ProtocolArea.UPLOADS)
        assertEquals(2, fetches)
    }

    @Test
    fun aPauseIsCheckedSoonerSoWritesResumeShortlyAfterItLifts() = runBlocking {
        document = paused("uploads")
        val status = status()
        assertNotNull(status.pauseFor(ProtocolArea.UPLOADS))

        document = CLEAR
        clock += RemoteProtocolStatus.PAUSED_TTL_MILLIS - 1
        assertNotNull("Still within the cached pause", status.pauseFor(ProtocolArea.UPLOADS))
        clock += 1
        assertNull(status.pauseFor(ProtocolArea.UPLOADS))
        assertTrue(status.pauses.first().isEmpty())
        assertEquals(2, fetches)
    }

    @Test
    fun aFailedFetchKeepsTheLastGoodStatusThenFailsOpen() = runBlocking {
        document = paused("uploads")
        val status = status()
        assertNotNull(status.pauseFor(ProtocolArea.UPLOADS))

        document = null
        clock += RemoteProtocolStatus.PAUSED_TTL_MILLIS
        assertNotNull("A pause fetched minutes ago still holds while GitHub is unreachable", status.pauseFor(ProtocolArea.UPLOADS))
        assertEquals(2, fetches)

        clock += RemoteProtocolStatus.RETRY_AFTER_FAILURE_MILLIS - 1
        status.pauseFor(ProtocolArea.UPLOADS)
        assertEquals("A failed fetch waits before trying again", 2, fetches)
        clock += 1
        status.pauseFor(ProtocolArea.UPLOADS)
        assertEquals(3, fetches)

        clock = memory.saved!!.fetchedAtEpochMillis + RemoteProtocolStatus.MAX_STALE_MILLIS + 1
        assertNull("Too old to trust: nothing is paused", status.pauseFor(ProtocolArea.UPLOADS))
        assertTrue(status.pauses.first().isEmpty())
    }

    @Test
    fun aMalformedDocumentNeitherPausesNorLiftsAPause() = runBlocking {
        document = paused("uploads")
        val status = status()
        status.pauseFor(ProtocolArea.UPLOADS)

        document = """{ "schemaVersion": 1, "pauses": [ { "id": "x", "areas": "uploads" } ] }"""
        clock += RemoteProtocolStatus.PAUSED_TTL_MILLIS
        assertNotNull(status.pauseFor(ProtocolArea.UPLOADS))
        assertEquals("The refused document is not cached", paused("uploads"), memory.saved?.document)
    }

    @Test
    fun aMalformedFirstDocumentPausesNothing() = runBlocking {
        document = "<html>rate limited</html>"
        assertNull(status().pauseFor(ProtocolArea.UPLOADS))
        assertNull(memory.saved)
    }

    @Test
    fun versionRangesSelectWhichAppsArePaused() = runBlocking {
        document = """{ "schemaVersion": 1, "pauses": [ { "id": "old", "areas": ["all"], "versions": { "max": "0.3.0" } } ] }"""

        assertNull(status(version = "0.4.0").pauseFor(ProtocolArea.UPLOADS))
        assertNotNull(status(version = "0.3.0").pauseFor(ProtocolArea.SHARED_ALBUMS))
        assertNotNull(status(version = "0.2.9-dev").pauseFor(ProtocolArea.LIBRARY_EDITS))
    }

    @Test
    fun theCachedStatusOutlivesTheProcess() = runBlocking {
        val directory = Files.createTempDirectory("protocol-status").toFile()
        try {
            val file = java.io.File(directory, "protocol-status")
            document = paused("uploads")
            assertNotNull(status(cache = FileProtocolStatusCache(file)).pauseFor(ProtocolArea.UPLOADS))

            // A new process starts within the cache's lifetime: it reads the saved status and asks nothing.
            document = null
            fetches = 0
            val restarted = status(cache = FileProtocolStatusCache(file))
            assertEquals(listOf("pause"), restarted.pauses.first().map { it.id })
            assertNotNull(restarted.pauseFor(ProtocolArea.UPLOADS))
            assertEquals(0, fetches)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aClockThatMovedBackRefetches() = runBlocking {
        val status = status()
        status.pauseFor(ProtocolArea.UPLOADS)
        clock -= 60_000
        status.pauseFor(ProtocolArea.UPLOADS)
        assertEquals(2, fetches)
    }

    @Test
    fun observersGetTheCachedStatusWhileAFetchIsSlow() = runBlocking {
        document = paused("uploads")
        val status = status()
        assertNotNull(status.pauseFor(ProtocolArea.UPLOADS))

        // The cached copy goes stale and the next fetch hangs, as on a slow network.
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val slow = RemoteProtocolStatus(
            fetch = { started.complete(Unit); release.await(); CLEAR },
            cache = memory,
            appVersionName = "0.4.0",
            now = { clock },
        )
        clock += RemoteProtocolStatus.PAUSED_TTL_MILLIS
        val writer = async(Dispatchers.Default) { slow.pauseFor(ProtocolArea.UPLOADS) }
        started.await()

        val seen = withTimeout(1_000) { slow.pauses.first() }
        assertEquals("The Backup page sees the cached pause at once", listOf("pause"), seen.map { it.id })

        release.complete(Unit)
        assertNull("The writer waits for the fresh copy", writer.await())
        assertTrue(slow.pauses.first().isEmpty())
    }

    @Test
    fun theFetcherOnlyUsesHttps() {
        try {
            OkHttpProtocolStatusFetcher("http://example.com/status.json")
            org.junit.Assert.fail("Accepted a plain HTTP status URL")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
        val fetcher = OkHttpProtocolStatusFetcher(RemoteProtocolStatus.DEFAULT_URL)
        assertFalse("A redirect from HTTPS to HTTP is not followed", fetcher.client.followSslRedirects)
        OkHttpProtocolStatusFetcher.requireHttps("https://raw.githubusercontent.com/status.json".toHttpUrl())
        try {
            OkHttpProtocolStatusFetcher.requireHttps("http://raw.githubusercontent.com/status.json".toHttpUrl())
            org.junit.Assert.fail("Accepted a status served over plain HTTP")
        } catch (_: IOException) {
            // Expected: treated like an unreachable status.
        }
    }

    private companion object {
        const val CLEAR = """{ "schemaVersion": 1, "pauses": [] }"""

        fun paused(area: String) =
            """{ "schemaVersion": 1, "pauses": [ { "id": "pause", "areas": ["$area"], "message": { "en": "Paused for a change." } } ] }"""
    }
}
