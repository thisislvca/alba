package me.lvca.alba.protocol.status

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.lvca.alba.engine.model.ProtocolArea
import me.lvca.alba.engine.model.ProtocolPause
import me.lvca.alba.engine.model.ProtocolStatusGate
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A status document as last fetched, and when. */
class CachedProtocolStatus(val document: String, val fetchedAtEpochMillis: Long) {
    internal val entries: List<PauseEntry> by lazy { ProtocolStatusDocument.parse(document) }
}

/** Where the last good status document is kept between processes. */
interface ProtocolStatusCache {
    fun read(): CachedProtocolStatus?
    fun write(status: CachedProtocolStatus)
}

/**
 * Alba's remote protocol status: a small JSON document Alba's maintainers publish (see [ProtocolStatusDocument]) that
 * can pause write paths of the iCloud web protocol for some app versions, for example while an iCloud change is
 * investigated.
 *
 * It fails open. The document is fetched only when a write asks and the cached copy is stale; a failed fetch keeps the
 * last good copy for [MAX_STALE_MILLIS] and then lets everything run, and is not tried again for
 * [RETRY_AFTER_FAILURE_MILLIS]. A malformed document is refused rather than cached, so it neither pauses nor lifts
 * anything. A pause in effect is checked again sooner than a clear status, so writes resume soon after it lifts.
 */
class RemoteProtocolStatus(
    private val fetch: suspend () -> String,
    private val cache: ProtocolStatusCache,
    appVersionName: String,
    private val now: () -> Long = System::currentTimeMillis,
) : ProtocolStatusGate {
    private val version = AppVersion.parse(appVersionName)

    /** Held only to read the disk cache once; never across a fetch, so observers never wait for the network. */
    private val loadLock = Mutex()

    /** One fetch at a time. Writers wait here for a fresh copy, at most the fetcher's call timeout. */
    private val fetchLock = Mutex()
    private val state = MutableStateFlow<List<ProtocolPause>>(emptyList())

    @Volatile private var loaded = false

    @Volatile private var current: CachedProtocolStatus? = null

    @Volatile private var nextAttemptAt = 0L

    /** The cached state, at once: collecting it never waits for a fetch in flight. */
    override val pauses: Flow<List<ProtocolPause>> = state.onStart { load(); publish(now()) }

    override suspend fun pauseFor(area: ProtocolArea): ProtocolPause? = refresh().firstOrNull { area in it.areas }

    /** The pauses in effect for this app version, after fetching the document when the cached one is stale. */
    suspend fun refresh(): List<ProtocolPause> {
        load()
        fetchLock.withLock {
            // Checked again under the lock: a fetch that finished while this one waited has already refreshed it.
            val at = now()
            if (!isFresh(current, at) && at >= nextAttemptAt) fetchNow(at)
        }
        return publish(now())
    }

    private suspend fun fetchNow(at: Long) {
        try {
            val document = fetch()
            val fetched = CachedProtocolStatus(document, at).also { it.entries }
            current = fetched
            nextAttemptAt = 0L
            runCatching { withContext(Dispatchers.IO) { cache.write(fetched) } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Unreachable, refused or malformed: keep what was known, and wait before asking again.
            nextAttemptAt = at + RETRY_AFTER_FAILURE_MILLIS
        }
    }

    private suspend fun load() {
        if (loaded) return
        loadLock.withLock {
            if (loaded) return
            val cached = runCatching { withContext(Dispatchers.IO) { cache.read() }?.also { it.entries } }.getOrNull()
            // A fetch may have finished first; never replace a fresher copy with the disk's.
            if (current == null) current = cached
            loaded = true
        }
    }

    private fun publish(at: Long): List<ProtocolPause> {
        val usable = current?.takeIf { age(it, at) in 0..MAX_STALE_MILLIS }
        return (usable?.let { effectivePauses(it.entries, version) } ?: emptyList()).also { state.value = it }
    }

    private fun isFresh(status: CachedProtocolStatus?, at: Long): Boolean {
        if (status == null) return false
        val paused = effectivePauses(status.entries, version).isNotEmpty()
        return age(status, at) in 0 until if (paused) PAUSED_TTL_MILLIS else CLEAR_TTL_MILLIS
    }

    private fun age(status: CachedProtocolStatus, at: Long): Long = at - status.fetchedAtEpochMillis

    companion object {
        private const val MINUTE = 60_000L

        /** A clear status is fetched again after this long, so a new pause reaches a phone within about that time. */
        const val CLEAR_TTL_MILLIS = 30 * MINUTE

        /** A pause in effect is checked again after this long, so writes resume soon after it is lifted. */
        const val PAUSED_TTL_MILLIS = 10 * MINUTE

        /** A failed fetch is not tried again for this long; the last good status stays in use meanwhile. */
        const val RETRY_AFTER_FAILURE_MILLIS = 5 * MINUTE

        /** The last good status counts for this long without a fresh fetch; after that nothing is paused. */
        const val MAX_STALE_MILLIS = 12 * 60 * MINUTE

        /** Where official builds read the status: the file on the repository's main branch. */
        const val DEFAULT_URL = "https://raw.githubusercontent.com/thisislvca/alba/main/status/protocol-status.json"

        /** The status for [appVersionName], fetched from [url] and kept in [cacheFile]. */
        fun create(url: String, cacheFile: File, appVersionName: String): RemoteProtocolStatus =
            RemoteProtocolStatus(OkHttpProtocolStatusFetcher(url), FileProtocolStatusCache(cacheFile), appVersionName)
    }
}

/** Keeps the last good document in [file], written whole through a temporary file. */
class FileProtocolStatusCache(private val file: File) : ProtocolStatusCache {
    override fun read(): CachedProtocolStatus? {
        if (!file.isFile) return null
        val text = file.readText()
        val fetchedAt = text.substringBefore('\n').toLongOrNull() ?: return null
        return CachedProtocolStatus(text.substringAfter('\n'), fetchedAt)
    }

    override fun write(status: CachedProtocolStatus) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText("${status.fetchedAtEpochMillis}\n${status.document}")
        if (!temporary.renameTo(file)) {
            file.delete()
            check(temporary.renameTo(file)) { "Could not save the protocol status" }
        }
    }
}

/**
 * Fetches the status document from [url] with a plain GET: no cookies, no Apple session, no account or device
 * identifiers, and nothing in the query. Short timeouts keep a slow host from holding up a backup pass.
 */
class OkHttpProtocolStatusFetcher(
    private val url: String,
    client: OkHttpClient = OkHttpClient(),
) : suspend () -> String {
    init {
        require(url.startsWith("https://")) { "The protocol status must be fetched over HTTPS" }
    }

    /** Redirects between HTTPS hosts are followed; one to plain HTTP is not, and is refused below as a non-200. */
    internal val client = client.newBuilder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followSslRedirects(false)
        .cache(null)
        .build()

    override suspend fun invoke(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            requireHttps(response.request.url)
            if (response.code != HTTP_OK) throw IOException("The protocol status returned HTTP ${response.code}")
            val source = response.body.source()
            if (source.request(MAX_BYTES + 1L)) throw IOException("The protocol status is too large")
            source.readUtf8()
        }
    }

    internal companion object {
        /** Refuses a response that, after any redirect, did not come over HTTPS. */
        fun requireHttps(finalUrl: HttpUrl) {
            if (!finalUrl.isHttps) throw IOException("The protocol status was not served over HTTPS")
        }

        const val HTTP_OK = 200
        const val TIMEOUT_SECONDS = 5L
        const val CALL_TIMEOUT_SECONDS = 8L
        const val MAX_BYTES = 64 * 1024
    }
}
