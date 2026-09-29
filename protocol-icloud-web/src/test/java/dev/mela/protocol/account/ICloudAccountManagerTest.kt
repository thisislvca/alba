package dev.mela.protocol.account

import dev.mela.protocol.auth.AppleAuthenticationService
import dev.mela.protocol.network.*
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ICloudAccountManagerTest {
    @Test fun localCleanupCanDrainRequestsWithoutResurrectingTheSession() = runBlocking {
        val store = MemoryStore()
        lateinit var manager: ICloudAccountManager
        manager = ICloudAccountManager(AppleAuthenticationService(ValidationTransport(200)), store,
            onLocalSignOut = {
                // A request completing while media cleanup waits for it must not deadlock.
                withTimeout(2_000) { manager.updateSession(SNAPSHOT) }
            })
        manager.restore()
        manager.signOut()
        assertNull(store.saved)
        assertNull(manager.session.value)
    }

    @Test fun cancelledLogoutClearsLocalSessionAndMediaBeforeContactingApple() = runBlocking {
        var saved: AppleSessionSnapshot? = SNAPSHOT
        var mediaCleared = false
        val store = object : AppleSessionStore {
            override suspend fun load() = saved
            override suspend fun save(snapshot: AppleSessionSnapshot) { saved = snapshot }
            override suspend fun clear() = withContext(Dispatchers.IO) { saved = null }
        }
        val reachedLogout = CompletableDeferred<Unit>()
        val delegate = ValidationTransport(200)
        val transport = object : AppleHttpTransport by delegate {
            override suspend fun execute(request: AppleHttpRequest): AppleHttpResponse {
                if (request.url.contains("/logout")) {
                    assertNull(saved)
                    assertTrue(mediaCleared)
                    reachedLogout.complete(Unit)
                    awaitCancellation()
                }
                return delegate.execute(request)
            }
        }
        val manager = ICloudAccountManager(AppleAuthenticationService(transport), store,
            onLocalSignOut = { withContext(Dispatchers.IO) { mediaCleared = true } })
        manager.restore()
        val logout = launch { manager.signOut() }
        withTimeout(5_000) { reachedLogout.await() }
        logout.cancelAndJoin()
        assertEquals(ICloudAccountState.Demo, manager.state.value)
        assertNull(saved)
        val restarted = ICloudAccountManager(AppleAuthenticationService(delegate), store)
        restarted.restore()
        assertEquals(ICloudAccountState.Demo, restarted.state.value)
    }

    @Test fun cancellationDuringLocalLogoutStillFinishesDurableCleanup() = runBlocking {
        val clearing = CompletableDeferred<Unit>()
        val finishClear = CompletableDeferred<Unit>()
        var saved: AppleSessionSnapshot? = SNAPSHOT
        var mediaCleared = false
        val store = object : AppleSessionStore {
            override suspend fun load() = saved
            override suspend fun save(snapshot: AppleSessionSnapshot) { saved = snapshot }
            override suspend fun clear() = withContext(Dispatchers.IO) {
                clearing.complete(Unit)
                finishClear.await()
                saved = null
            }
        }
        val manager = ICloudAccountManager(AppleAuthenticationService(ValidationTransport(200)), store,
            onLocalSignOut = { mediaCleared = true })
        manager.restore()
        val logout = launch { manager.signOut() }
        clearing.await()
        logout.cancel()
        finishClear.complete(Unit)
        logout.join()
        assertNull(saved)
        assertTrue(mediaCleared)
        assertEquals(ICloudAccountState.Demo, manager.state.value)
    }

    @Test fun storageFailureDoesNotPretendLogoutSucceeded() = runBlocking {
        val store = object : AppleSessionStore {
            override suspend fun load() = SNAPSHOT
            override suspend fun save(snapshot: AppleSessionSnapshot) = Unit
            override suspend fun clear(): Unit = throw IOException("storage unavailable")
        }
        val manager = ICloudAccountManager(AppleAuthenticationService(ValidationTransport(200)), store)
        manager.restore()
        assertTrue(runCatching { manager.signOut() }.isFailure)
        assertNotNull(manager.session.value)
        assertTrue(manager.state.value is ICloudAccountState.SignedIn)
    }

    @Test fun `session telemetry reports fixed outcomes without account data`() = runBlocking {
        val telemetry = RecordingTelemetry()
        val authentication = AppleAuthenticationService(ValidationTransport(200), telemetry = telemetry)
        val manager = ICloudAccountManager(authentication, MemoryStore(), telemetry)

        manager.restore()
        manager.rejectSession(SNAPSHOT)

        assertTrue(telemetry.records.any { it.first == AccountOperation.VALIDATE && it.second == AccountOutcome.SUCCESS })
        assertTrue(telemetry.records.any { it.first == AccountOperation.RESTORE && it.second == AccountOutcome.SUCCESS })
        assertTrue(telemetry.records.any { it.first == AccountOperation.SESSION_REJECTED && it.second == AccountOutcome.EXPIRED })
        assertTrue(telemetry.records.all { it.third >= 0L })
        assertTrue(telemetry.cookieLifetimes.any { it != null })
    }

    @Test fun `cookie telemetry reports only aggregate remaining lifetime`() {
        val now = 1_000_000L
        val snapshot = SNAPSHOT.copy(cookies = listOf(
            PersistedCookie("session", "secret", ".icloud.com", "/", null, true, true, false),
            PersistedCookie("expired", "secret", ".icloud.com", "/", now - 1L, true, true, false),
            PersistedCookie("short", "secret", ".icloud.com", "/", now + 3_600_000L, true, true, false),
            PersistedCookie("long", "secret", ".icloud.com", "/", now + 7_200_000L, true, true, false),
        ))

        assertEquals(
            CookieLifetimeMetrics(
                persistentCookieCount = 3,
                sessionCookieCount = 1,
                expiredPersistentCookieCount = 1,
                earliestRemainingMillis = 0L,
                latestRemainingMillis = 7_200_000L,
            ),
            snapshot.cookieLifetimeMetrics(now),
        )
    }

    @Test fun `offline and server failures retain session and can retry without authorizing writes`() = runBlocking {
        for (code in listOf(0, 503)) {
            val store = MemoryStore()
            val transport = ValidationTransport(code)
            val manager = ICloudAccountManager(AppleAuthenticationService(transport), store)
            manager.restore()
            assertEquals(SessionStatus.OFFLINE, (manager.state.value as ICloudAccountState.SignedIn).status)
            assertEquals(SNAPSHOT, manager.session.value)
            assertEquals(SNAPSHOT, store.saved)
            assertEquals(0, store.clears)
            assertNull(manager.authorizedSession)
            transport.code = 200
            manager.restore()
            assertEquals(SessionStatus.VERIFIED, (manager.state.value as ICloudAccountState.SignedIn).status)
            assertNotNull(manager.authorizedSession)
        }
    }

    @Test fun `expired authentication retains browsing identity until explicit sign out`() = runBlocking {
        val store = MemoryStore()
        val manager = ICloudAccountManager(AppleAuthenticationService(ValidationTransport(401)), store)
        manager.restore()
        assertEquals(SessionStatus.EXPIRED, (manager.state.value as ICloudAccountState.SignedIn).status)
        assertEquals(SNAPSHOT, manager.session.value)
        assertEquals(SNAPSHOT, store.saved)
        assertNull(manager.authorizedSession)
        manager.signOut()
        assertEquals(ICloudAccountState.Demo, manager.state.value)
        assertNull(store.saved)
        assertEquals(1, store.clears)
    }

    @Test fun `failed reauthentication preserves cached account identity`() = runBlocking {
        val store = MemoryStore()
        val manager = ICloudAccountManager(AppleAuthenticationService(ValidationTransport(0)), store)
        manager.restore()
        assertTrue(runCatching { manager.signIn("me@example.com", "password") }.isFailure)
        assertEquals(SNAPSHOT, manager.session.value)
        assertEquals(SNAPSHOT, store.saved)
        assertEquals(SessionStatus.OFFLINE, (manager.state.value as ICloudAccountState.SignedIn).status)
    }

    @Test fun `callbacks from removed or replaced authentication cannot restore saved session`() = runBlocking {
        val store = MemoryStore()
        val manager = ICloudAccountManager(AppleAuthenticationService(ValidationTransport(200)), store)
        manager.restore()
        val valid = manager.session.value
        manager.updateSession(SNAPSHOT.copy(clientId = "old-client", sessionToken = "stale"))
        assertEquals(valid, manager.session.value)
        manager.signOut()
        manager.updateSession(SNAPSHOT)
        assertNull(manager.session.value)
        assertNull(store.saved)
    }

    @Test fun `current rejection expires writes without deleting the saved session`() = runBlocking {
        val store = MemoryStore()
        val manager = ICloudAccountManager(AppleAuthenticationService(ValidationTransport(200)), store)
        manager.restore()

        manager.rejectSession(SNAPSHOT)

        assertEquals(SessionStatus.EXPIRED, (manager.state.value as ICloudAccountState.SignedIn).status)
        assertEquals(SNAPSHOT, manager.session.value)
        assertEquals(SNAPSHOT, store.saved)
        assertEquals(0, store.clears)
        assertNull(manager.authorizedSession)
    }

    @Test fun `stale rejection cannot expire a renewed session`() = runBlocking {
        val store = MemoryStore()
        val manager = ICloudAccountManager(AppleAuthenticationService(ValidationTransport(200)), store)
        manager.restore()
        val stale = SNAPSHOT.copy(sessionToken = "stale-token")

        manager.rejectSession(stale)

        assertEquals(SessionStatus.VERIFIED, (manager.state.value as ICloudAccountState.SignedIn).status)
        assertEquals(SNAPSHOT, manager.authorizedSession)
    }

    @Test fun `unreadable session storage does not clear saved account or select demo`() = runBlocking {
        var cleared = false
        val store = object : AppleSessionStore {
            override suspend fun load(): AppleSessionSnapshot? = throw IOException("Storage unavailable")
            override suspend fun save(snapshot: AppleSessionSnapshot) = Unit
            override suspend fun clear() { cleared = true }
        }
        val manager = ICloudAccountManager(AppleAuthenticationService(ValidationTransport(200)), store)
        assertTrue(runCatching { manager.restore() }.isFailure)
        assertEquals(ICloudAccountState.Restoring, manager.state.value)
        assertFalse(cleared)
    }

    @Test fun `web only account is retained but cannot authorize Photos work`() = runBlocking {
        val webOnly = SNAPSHOT.copy(webservices = emptyMap())
        val store = MemoryStore(webOnly)
        val telemetry = RecordingTelemetry()
        val manager = ICloudAccountManager(
            AppleAuthenticationService(ValidationTransport(200, photosAvailable = false)),
            store,
            telemetry,
        )

        manager.restore()

        assertEquals(SessionStatus.PHOTOS_NOT_ENABLED, (manager.state.value as ICloudAccountState.SignedIn).status)
        assertEquals(webOnly.copy(webservices = emptyMap()), manager.session.value)
        assertNull(manager.authorizedSession)
        assertEquals(0, store.clears)
        assertTrue(telemetry.records.any {
            it.first == AccountOperation.RESTORE && it.second == AccountOutcome.PHOTOS_NOT_ENABLED
        })
    }

    private class MemoryStore(initial: AppleSessionSnapshot? = SNAPSHOT) : AppleSessionStore {
        var saved: AppleSessionSnapshot? = initial
        var clears = 0
        override suspend fun load() = saved
        override suspend fun save(snapshot: AppleSessionSnapshot) { saved = snapshot }
        override suspend fun clear() { clears++; saved = null }
    }
    private class RecordingTelemetry : AccountTelemetry {
        val records = mutableListOf<Triple<AccountOperation, AccountOutcome, Long>>()
        val cookieLifetimes = mutableListOf<CookieLifetimeMetrics?>()
        override fun record(
            operation: AccountOperation,
            outcome: AccountOutcome,
            elapsedMillis: Long,
            cookieLifetime: CookieLifetimeMetrics?,
        ) {
            records += Triple(operation, outcome, elapsedMillis)
            cookieLifetimes += cookieLifetime
        }
    }
    private class ValidationTransport(var code: Int, private val photosAvailable: Boolean = true) : AppleHttpTransport {
        private var headers = AppleSessionHeaders()
        override val sessionHeaders: AppleSessionHeaders get() = headers
        override suspend fun execute(request: AppleHttpRequest): AppleHttpResponse {
            if (code == 0) throw IOException("Offline")
            val services = if (photosAvailable) """{"ckdatabasews":{"url":"https://p01-ckdatabasews.icloud.com"}}""" else "{}"
            return AppleHttpResponse(code, emptyMap(), """{"dsInfo":{"dsid":"123","hsaVersion":2},"hsaTrustedBrowser":true,"webservices":$services}""")
        }
        override suspend fun stream(url: String, output: OutputStream) = error("Unused")
        override fun snapshotCookies() = emptyList<PersistedCookie>()
        override fun restore(snapshot: AppleSessionSnapshot) {
            headers = AppleSessionHeaders(
                accountCountryCode = snapshot.accountCountryCode,
                sessionId = snapshot.sessionId,
                sessionToken = snapshot.sessionToken,
                trustToken = snapshot.trustToken,
            )
        }
        override fun clear() = Unit
    }
    companion object {
        val SNAPSHOT = AppleSessionSnapshot("me@example.com", "client", "IT", "session", "token", null, "123",
            mapOf("ckdatabasews" to "https://p01-ckdatabasews.icloud.com"), emptyList())
    }
}
