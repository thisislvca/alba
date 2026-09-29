package dev.mela.protocol.auth

import dev.mela.protocol.account.AppleSessionSnapshot
import dev.mela.protocol.account.AppleSignInResult
import dev.mela.protocol.account.PersistedCookie
import dev.mela.protocol.account.TwoFactorDelivery
import dev.mela.protocol.network.AppleHttpRequest
import dev.mela.protocol.network.AppleHttpResponse
import dev.mela.protocol.network.AppleHttpTransport
import dev.mela.protocol.network.AppleSessionHeaders
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleAuthenticationServiceTest {
    @Test fun authenticationRunsOnItsDispatcherAndReturnsToCaller() = runBlocking {
        val caller = Thread.currentThread()
        java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "auth-computation-test") }
            .asCoroutineDispatcher().use { dispatcher ->
                val delegate = ScriptedTransport()
                val threads = mutableListOf<String>()
                val transport = object : AppleHttpTransport by delegate {
                    override suspend fun execute(request: AppleHttpRequest): AppleHttpResponse {
                        threads += Thread.currentThread().name
                        return delegate.execute(request)
                    }
                }
                val service = AppleAuthenticationService(transport,
                    clientIdProvider = {
                        assertTrue(Thread.currentThread().name.startsWith("auth-computation-test"))
                        CLIENT_ID
                    }, computationDispatcher = dispatcher)
                service.signIn(APPLE_ID, PASSWORD)
                service.resendTwoFactor()
                service.submitTwoFactor("123456")
                assertTrue(threads.isNotEmpty())
                assertTrue(threads.all { it.startsWith("auth-computation-test") })
                assertEquals(caller, Thread.currentThread())
            }
    }

    @Test fun `restoration preserves verified profile name and account services`() = runBlocking {
        val transport = ScriptedTransport(validateBody = """{
            "dsInfo":{"dsid":"123456789","hsaVersion":2,"fullName":"Alex Example"},
            "hsaTrustedBrowser":true,
            "webservices":{"ckdatabasews":{"url":"https://p02-ckdatabasews.icloud.com"},
                "account":{"url":"https://p02-setup.icloud.com"},
                "contacts":{"url":"https://p02-contactsws.icloud.com"}}
        }""")
        val restored = requireNotNull(AppleAuthenticationService(transport).restore(savedSession()))
        assertEquals("Alex Example", restored.displayName)
        assertEquals("https://p02-contactsws.icloud.com", restored.webservices["contacts"])
        assertEquals("https://p02-setup.icloud.com", restored.webservices["account"])
    }

    @Test
    fun `expired validation renews with saved token and trust without password flow`() = runBlocking {
        val saved = savedSession()
        val transport = ScriptedTransport(validateCode = 401)
        val service = AppleAuthenticationService(transport, clientIdProvider = { "replacement-client" })

        val restored = requireNotNull(service.restore(saved))

        assertEquals(saved.clientId, restored.clientId)
        assertEquals(saved.accountName, restored.accountName)
        assertEquals(
            listOf("POST /setup/ws/1/validate", "POST /setup/ws/1/accountLogin"),
            transport.requests.map { "${it.method} ${pathWithoutQuery(it.url)}" },
        )
        val login = transport.requests.last()
        assertTrue(login.url.contains("clientId=${saved.clientId}"))
        assertTrue(login.body.orEmpty().contains("\"dsWebAuthToken\":\"${saved.sessionToken}\""))
        assertTrue(login.body.orEmpty().contains("\"trustToken\":\"${saved.trustToken}\""))
        assertTrue(login.body.orEmpty().contains("\"extended_login\":true"))
        assertEquals(0, transport.clearCount)
    }

    @Test
    fun `same account password renewal keeps trusted identity while account switch clears it`() = runBlocking {
        val saved = savedSession()
        val trustToken = requireNotNull(saved.trustToken)
        val sameAccount = ScriptedTransport()
        val sameService = AppleAuthenticationService(sameAccount, clientIdProvider = { "replacement-client" })

        sameService.signIn(saved.accountName.uppercase(), PASSWORD, saved)

        assertEquals(0, sameAccount.clearCount)
        assertEquals(saved, sameAccount.restoredSession)
        assertTrue(sameAccount.requests.first().url.contains("state=${saved.clientId}"))
        assertTrue(sameAccount.requests.first { pathWithoutQuery(it.url) == "/appleauth/auth/signin/complete" }
            .body.orEmpty().contains(trustToken))

        val switchedAccount = ScriptedTransport()
        val switchedService = AppleAuthenticationService(switchedAccount, clientIdProvider = { "replacement-client" })
        switchedService.signIn("other@example.com", PASSWORD, saved)

        assertEquals(1, switchedAccount.clearCount)
        assertEquals(null, switchedAccount.restoredSession)
        assertTrue(switchedAccount.requests.first().url.contains("state=replacement-client"))
        assertFalse(switchedAccount.requests.first { pathWithoutQuery(it.url) == "/appleauth/auth/signin/complete" }
            .body.orEmpty().contains(trustToken))
    }

    @Test
    fun `restored service inventory replaces stale upload partition or removes it`() = runBlocking {
        for (uploadService in listOf("", """, "photosupload":{"url":"https://p42-photosupload.icloud.com"}""")) {
            val transport = ScriptedTransport(validateBody = """{
                "dsInfo":{"dsid":"123456789","hsaVersion":2},
                "hsaTrustedBrowser":true,
                "webservices":{"ckdatabasews":{"url":"https://p02-ckdatabasews.icloud.com"}$uploadService}
            }""")
            val service = AppleAuthenticationService(transport, clientIdProvider = { CLIENT_ID })
            service.signIn(APPLE_ID, PASSWORD)
            val saved = service.submitTwoFactor("123456")
            val restored = requireNotNull(service.restore(saved))
            assertEquals("https://p02-ckdatabasews.icloud.com", restored.webservices["ckdatabasews"])
            assertEquals(if (uploadService.isEmpty()) null else "https://p42-photosupload.icloud.com",
                restored.webservices["photosupload"])
        }
    }

    @Test
    fun `web only account remains a valid session without a Photos service`() = runBlocking {
        val transport = ScriptedTransport(validateBody = """{
            "dsInfo":{"dsid":"123456789","hsaVersion":2},
            "hsaTrustedBrowser":true,
            "webservices":{}
        }""")
        val service = AppleAuthenticationService(transport, clientIdProvider = { CLIENT_ID })

        val restored = requireNotNull(service.restore(savedSession()))

        assertTrue(restored.webservices.isEmpty())
        assertEquals("123456789", restored.dsid)
    }

    @Test
    fun `trusted device flow establishes resumable session without sending password`() = runBlocking {
        val transport = ScriptedTransport()
        val service = AppleAuthenticationService(
            transport = transport,
            clientIdProvider = { CLIENT_ID },
        )

        val signIn = service.signIn(APPLE_ID, PASSWORD)

        assertEquals(
            AppleSignInResult.RequiresTwoFactor(
                appleId = APPLE_ID,
                delivery = TwoFactorDelivery.TRUSTED_DEVICE,
            ),
            signIn,
        )

        service.resendTwoFactor()
        val snapshot = service.submitTwoFactor("123456")

        assertEquals(APPLE_ID, snapshot.accountName)
        assertEquals(CLIENT_ID, snapshot.clientId)
        assertEquals("123456789", snapshot.dsid)
        assertEquals("https://p01-ckdatabasews.icloud.com", snapshot.webservices["ckdatabasews"])
        assertEquals("https://p99-photosupload.icloud.com", snapshot.webservices["photosupload"])
        assertEquals("session-token", snapshot.sessionToken)
        assertEquals("trust-token", snapshot.trustToken)
        assertEquals(listOf("session-cookie"), snapshot.cookies.map(PersistedCookie::name))
        assertEquals(
            listOf(
                "GET /appleauth/auth/authorize/signin",
                "POST /appleauth/auth/signin/init",
                "POST /appleauth/auth/signin/complete",
                "GET /appleauth/auth",
                "GET /appleauth/auth/verify/trusteddevice",
                "GET /appleauth/auth/verify/trusteddevice",
                "POST /appleauth/auth/verify/trusteddevice/securitycode",
                "GET /appleauth/auth/2sv/trust",
                "POST /setup/ws/1/accountLogin",
            ),
            transport.requests.map { "${it.method} ${pathWithoutQuery(it.url)}" },
        )
        val serializedRequests = transport.requests.joinToString("\n") { request ->
            request.headers.toString() + request.body.orEmpty()
        }
        assertFalse(serializedRequests.contains(PASSWORD))
        assertTrue(transport.requests[1].body.orEmpty().contains("\"a\""))
        assertTrue(transport.requests[1].headers.containsKey("X-Apple-FD-Client-Info"))
        assertTrue(transport.requests[2].body.orEmpty().contains("\"m1\""))
        assertTrue(transport.requests[6].body.orEmpty().contains("123456"))
    }

    @Test
    fun `modern trusted device route uses bridge and can reopen its prompt`() = runBlocking {
        val transport = ScriptedTransport(bootHtml = BRIDGE_BOOT_HTML, authTypeKey = "authenticationType")
        val bridge = FakeBridge()
        val service = AppleAuthenticationService(
            transport = transport,
            clientIdProvider = { CLIENT_ID },
            trustedDeviceBridge = bridge,
            testConstructor = Unit,
        )

        val signIn = service.signIn(APPLE_ID, PASSWORD)
        assertEquals(
            AppleSignInResult.RequiresTwoFactor(
                appleId = APPLE_ID,
                delivery = TwoFactorDelivery.TRUSTED_DEVICE,
            ),
            signIn,
        )
        service.resendTwoFactor()
        val snapshot = service.submitTwoFactor("050044")

        assertEquals(2, bridge.startCount)
        assertEquals("050044", bridge.validatedCode)
        assertEquals(2, bridge.closeCount)
        assertEquals("123456789", snapshot.dsid)
        assertEquals(
            listOf(
                "GET /appleauth/auth/authorize/signin",
                "POST /appleauth/auth/signin/init",
                "POST /appleauth/auth/signin/complete",
                "GET /appleauth/auth",
                "GET /appleauth/auth/2sv/trust",
                "POST /setup/ws/1/accountLogin",
            ),
            transport.requests.map { "${it.method} ${pathWithoutQuery(it.url)}" },
        )
    }

    private class FakeBridge : TrustedDeviceBridgeClient {
        var startCount = 0
        var closeCount = 0
        var validatedCode: String? = null

        override suspend fun start(
            context: Hsa2BootContext,
            headers: Map<String, String>,
        ): TrustedDeviceBridgeState {
            assertTrue(context.supportsTrustedDeviceBridge)
            assertTrue(headers.containsKey("X-Apple-FD-Client-Info"))
            startCount += 1
            return TrustedDeviceBridgeState(
                connectionPath = "connection-$startCount",
                pushToken = "push-token",
                sessionId = "session-$startCount",
                socket = NoOpBridgeSocket(),
                topic = "com.apple.idmsauthwidget",
                sourceAppId = "1159",
                nextStep = "2",
                transactionId = "2300_282820214_S",
                salt = "c2FsdA==",
            )
        }

        override suspend fun validateCode(
            state: TrustedDeviceBridgeState,
            headers: Map<String, String>,
            code: String,
        ): Boolean {
            validatedCode = code
            return true
        }

        override fun close(state: TrustedDeviceBridgeState?) {
            if (state?.socket == null) return
            state.socket = null
            closeCount += 1
        }
    }

    private class NoOpBridgeSocket : BridgeSocket {
        override suspend fun readMessage(): ByteArray = error("Not used")
        override fun send(payload: ByteArray) = Unit
        override fun close() = Unit
    }

    private class ScriptedTransport(
        private val bootHtml: String = LEGACY_BOOT_HTML,
        private val authTypeKey: String = "authType",
        private val validateBody: String = "{}",
        private val validateCode: Int = 200,
        private val accountLoginCode: Int = 200,
    ) : AppleHttpTransport {
        val requests = mutableListOf<AppleHttpRequest>()
        var clearCount = 0
        var restoredSession: AppleSessionSnapshot? = null
        private var currentHeaders = AppleSessionHeaders()

        override val sessionHeaders: AppleSessionHeaders
            get() = currentHeaders

        override suspend fun execute(request: AppleHttpRequest): AppleHttpResponse {
            requests += request
            val path = pathWithoutQuery(request.url)
            return when (path) {
                "/appleauth/auth/authorize/signin" -> response(200, "<html></html>")
                "/appleauth/auth/signin/init" -> response(
                    200,
                    """
                    {
                      "salt":"ABEiM0RVZneImaq7zN3u/w==",
                      "b":"$SERVER_PUBLIC_B",
                      "c":"challenge-id",
                      "iteration":2048,
                      "protocol":"s2k"
                    }
                    """.trimIndent(),
                )

                "/appleauth/auth/signin/complete" -> {
                    currentHeaders = AppleSessionHeaders(
                        accountCountryCode = "IT",
                        sessionId = "session-id",
                        sessionToken = "session-token",
                        trustToken = "trust-token",
                        scnt = "scnt-value",
                        authAttributes = "auth-attributes",
                    )
                    response(409, "{\"$authTypeKey\":\"hsa2\"}")
                }

                "/appleauth/auth" -> response(200, bootHtml)
                "/setup/ws/1/validate" -> response(validateCode, validateBody)

                "/appleauth/auth/verify/trusteddevice" -> response(200, "{}")
                "/appleauth/auth/verify/trusteddevice/securitycode" -> response(200, "{}")
                "/appleauth/auth/2sv/trust" -> response(200, "{}")
                "/setup/ws/1/accountLogin" -> response(
                    accountLoginCode,
                    """
                    {
                      "dsInfo":{"dsid":"123456789","hsaVersion":2},
                      "hsaTrustedBrowser":true,
                      "webservices":{
                        "ckdatabasews":{"url":"https://p01-ckdatabasews.icloud.com"},
                        "photosupload":{"url":"https://p99-photosupload.icloud.com"}
                      }
                    }
                    """.trimIndent(),
                )

                else -> error("Unexpected request: ${request.method} ${request.url}")
            }
        }

        override suspend fun stream(url: String, output: OutputStream) = error("Not used")

        override fun snapshotCookies(): List<PersistedCookie> = listOf(
            PersistedCookie(
                name = "session-cookie",
                value = "cookie-value",
                domain = "icloud.com",
                path = "/",
                expiresAtEpochMillis = null,
                secure = true,
                httpOnly = true,
                hostOnly = false,
            ),
        )

        override fun restore(snapshot: AppleSessionSnapshot) {
            restoredSession = snapshot
            currentHeaders = AppleSessionHeaders(
                accountCountryCode = snapshot.accountCountryCode,
                sessionId = snapshot.sessionId,
                sessionToken = snapshot.sessionToken,
                trustToken = snapshot.trustToken,
            )
        }

        override fun clear() {
            clearCount += 1
            currentHeaders = AppleSessionHeaders()
        }

        private fun response(code: Int, body: String) = AppleHttpResponse(
            code = code,
            headers = emptyMap(),
            body = body,
        )
    }

    private companion object {
        const val APPLE_ID = "luca@example.com"
        const val PASSWORD = "correct horse battery staple"
        const val CLIENT_ID = "test-client-id"
        const val SERVER_PUBLIC_B = "rGvbQTJKmpvxZt5eE4lYL69ytmUZh+4H/DGSlD21YFCjcynLtKCZ7YGT4HV3Z6E91SMSq0sDMQ3Nf0ip2gT9UOgIOWntt2ewz2CVF5oWOrNmGgX71fqq6CkYqZYvC5O4Vfl5k+yXXuqoDXQK2/T/dHNZ0EHVwz6nHSgeRGsUdzvKl7Q6I/uAFna9IHpDbGSB8dK5B4cXRhpbnTLmiPh3SFRFI7UksNV9Xqd6J3XS7PoDLPvb9S+zeGFgJ5AE5Xrmr4dOcwPOUymczAQce8MI2CpWmPOo0MOCca41+Onb+7aUtcgD2J965DXeI21SX1R1m2XjcvzWjvIPpxEflu8yXg=="
        val LEGACY_BOOT_HTML = """
            <html>
              <script class="boot_args" type="application/json">
                {"direct":{"authInitialRoute":"auth/verify/trusteddevice","hasTrustedDevices":true,"twoSV":{}}}
              </script>
            </html>
        """.trimIndent()
        val BRIDGE_BOOT_HTML = """
            <html>
              <script type="application/json" class="boot_args">
                {
                  "direct": {
                    "authInitialRoute": "auth/bridge/step",
                    "hasTrustedDevices": true,
                    "twoSV": {
                      "sourceAppId": 1159,
                      "authFactors": ["web_piggybacking", "sms"],
                      "bridgeInitiateData": {
                        "apnsTopic": "com.apple.idmsauthwidget",
                        "apnsEnvironment": "prod",
                        "webSocketUrl": "websocket.push.apple.com"
                      }
                    }
                  }
                }
              </script>
            </html>
        """.trimIndent()

        fun pathWithoutQuery(url: String): String = java.net.URI(url).path

        fun savedSession() = AppleSessionSnapshot(
            accountName = APPLE_ID,
            clientId = "saved-client-id",
            accountCountryCode = "IT",
            sessionId = "saved-session-id",
            sessionToken = "saved-session-token",
            trustToken = "saved-trust-token",
            dsid = "123456789",
            webservices = mapOf("ckdatabasews" to "https://p01-ckdatabasews.icloud.com"),
            cookies = emptyList(),
        )
    }
}
