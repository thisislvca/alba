package dev.mela.protocol.auth

import dev.mela.protocol.account.AppleSessionSnapshot
import dev.mela.protocol.account.AppleSignInResult
import dev.mela.protocol.account.AccountOperation
import dev.mela.protocol.account.AccountOutcome
import dev.mela.protocol.account.AccountTelemetry
import dev.mela.protocol.account.TwoFactorDelivery
import dev.mela.protocol.account.accountOutcome
import dev.mela.protocol.account.cookieLifetimeMetrics
import dev.mela.protocol.account.elapsedSince
import dev.mela.protocol.network.AppleEndpointPolicy
import dev.mela.protocol.network.AppleClientProfile
import dev.mela.protocol.network.AppleHttpRequest
import dev.mela.protocol.network.AppleHttpResponse
import dev.mela.protocol.network.AppleHttpTransport
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

class AppleAuthenticationService private constructor(
    private val transport: AppleHttpTransport,
    private val clientIdProvider: () -> String,
    private val trustedDeviceBridge: TrustedDeviceBridgeClient,
    private val telemetry: AccountTelemetry,
    private val computationDispatcher: CoroutineDispatcher,
) {
    constructor(
        transport: AppleHttpTransport,
        clientIdProvider: () -> String = { UUID.randomUUID().toString().lowercase() },
        telemetry: AccountTelemetry = AccountTelemetry.None,
        computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(
        transport = transport,
        clientIdProvider = clientIdProvider,
        trustedDeviceBridge = TrustedDeviceBridge(transport),
        telemetry = telemetry,
        computationDispatcher = computationDispatcher,
    )

    internal constructor(
        transport: AppleHttpTransport,
        clientIdProvider: () -> String,
        trustedDeviceBridge: TrustedDeviceBridgeClient,
        telemetry: AccountTelemetry = AccountTelemetry.None,
        computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
        @Suppress("UNUSED_PARAMETER") testConstructor: Unit = Unit,
    ) : this(transport, clientIdProvider, trustedDeviceBridge, telemetry, computationDispatcher)

    private val json = Json { ignoreUnknownKeys = true }
    private var pendingAuth: PendingAuth? = null

    suspend fun signIn(appleId: String, password: String, reusableSession: AppleSessionSnapshot? = null): AppleSignInResult =
        withContext(computationDispatcher) { signInInternal(appleId, password, reusableSession) }

    suspend fun submitTwoFactor(code: String): AppleSessionSnapshot =
        withContext(computationDispatcher) { submitTwoFactorInternal(code) }

    suspend fun resendTwoFactor() = withContext(computationDispatcher) { resendTwoFactorInternal() }

    suspend fun restore(snapshot: AppleSessionSnapshot): AppleSessionSnapshot? =
        withContext(computationDispatcher) { restoreInternal(snapshot) }

    private suspend fun signInInternal(
        appleId: String,
        password: String,
        reusableSession: AppleSessionSnapshot? = null,
    ): AppleSignInResult {
        val normalizedAppleId = appleId.trim()
        require(normalizedAppleId.isNotBlank()) { "Enter your Apple Account email" }
        require(password.isNotBlank()) { "Enter your Apple Account password" }
        clearPendingAuth()
        val trustedSession = reusableSession?.takeIf {
            it.accountName.equals(normalizedAppleId, ignoreCase = true)
        }
        if (trustedSession == null) transport.clear() else transport.restore(trustedSession)
        val clientId = trustedSession?.clientId ?: clientIdProvider()

        authorizeSignInShell(clientId)
        val srp = AppleSrpClient(normalizedAppleId, password)
        val challenge = beginSrp(normalizedAppleId, clientId, srp.publicA)
        val proof = srp.processChallenge(
            salt = decodeBase64(challenge.requiredString("salt")),
            serverPublicB = decodeBase64(challenge.requiredString("b")),
            iterations = challenge.requiredInt("iteration"),
            protocol = AppleSrpProtocol.fromWireValue(challenge.requiredString("protocol")),
        )
        val completion = completeSrp(
            appleId = normalizedAppleId,
            clientId = clientId,
            challengeId = challenge.requiredString("c"),
            proof = proof,
        )

        if (completion.code == HTTP_TWO_FACTOR_REQUIRED) {
            val completionBody = completion.jsonObjectOrNull()
            val authType = (completionBody?.get("authType") as? JsonPrimitive)?.contentOrNull
                ?: (completionBody?.get("authenticationType") as? JsonPrimitive)?.contentOrNull
            if (authType != "hsa2") {
                throw AppleProtocolException(
                    AppleProtocolError.UNSUPPORTED_TWO_FACTOR,
                    "This Apple Account returned an unsupported verification challenge.",
                )
            }
            val pending = requestTwoFactor(normalizedAppleId, clientId)
            pendingAuth = pending
            return AppleSignInResult.RequiresTwoFactor(
                appleId = normalizedAppleId,
                delivery = pending.delivery,
                destinationHint = pending.destinationHint,
            )
        }
        if (completion.code !in 200..299) throw invalidCredentials()

        val session = establishSession(normalizedAppleId, clientId)
        pendingAuth = null
        return AppleSignInResult.SignedIn(session)
    }

    private suspend fun submitTwoFactorInternal(code: String): AppleSessionSnapshot {
        val pending = requireNotNull(pendingAuth) { "No Apple verification challenge is active" }
        require(code.matches(Regex("\\d{6}"))) { "Enter the six-digit verification code" }
        val verified = when (pending.route) {
            PendingTwoFactorRoute.LEGACY_TRUSTED_DEVICE -> verifyLegacyTrustedDevice(
                pending = pending,
                code = code,
            )

            PendingTwoFactorRoute.TRUSTED_DEVICE_BRIDGE -> {
                val state = pending.bridgeState
                    ?: throw AppleProtocolException(
                        AppleProtocolError.SESSION_EXPIRED,
                        "The Apple verification prompt expired. Send another code and try again.",
                    )
                if (state.usesLegacyVerifier) {
                    try {
                        verifyLegacyTrustedDevice(pending, code)
                    } finally {
                        trustedDeviceBridge.close(state)
                    }
                } else {
                    val accepted = try {
                        trustedDeviceBridge.validateCode(state, authHeaders(pending.clientId), code)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        throw AppleProtocolException(
                            AppleProtocolError.SESSION_EXPIRED,
                            "Apple's verification prompt expired. Send another code and try again.",
                            error,
                        )
                    }
                    if (!accepted) {
                        pendingAuth = pending.copy(bridgeState = null)
                        throw invalidTwoFactorCode()
                    }
                    true
                }
            }

            PendingTwoFactorRoute.SMS -> {
                val verification = transport.execute(
                    AppleHttpRequest(
                        method = "POST",
                        url = "$AUTH_ENDPOINT/verify/phone/securitycode",
                        headers = authHeaders(pending.clientId),
                        body = buildJsonObject {
                            putJsonObject("phoneNumber") {
                                put("id", pending.phoneId ?: error("Missing trusted phone id"))
                            }
                            putJsonObject("securityCode") { put("code", code) }
                            put("mode", "sms")
                        }.toString(),
                    ),
                )
                if (verification.code !in 200..299) throw invalidTwoFactorCode()
                true
            }
        }
        if (!verified) throw invalidTwoFactorCode()

        val trust = transport.execute(
            AppleHttpRequest(
                method = "GET",
                url = "$AUTH_ENDPOINT/2sv/trust",
                headers = authHeaders(pending.clientId),
            ),
        )
        if (trust.code !in 200..299) {
            throw AppleProtocolException(
                AppleProtocolError.SESSION_EXPIRED,
                "Apple verified the code but did not trust this session.",
            )
        }

        val session = establishSession(pending.appleId, pending.clientId)
        clearPendingAuth()
        return session
    }

    private suspend fun verifyLegacyTrustedDevice(pending: PendingAuth, code: String): Boolean {
        val verification = transport.execute(
            AppleHttpRequest(
                method = "POST",
                url = "$AUTH_ENDPOINT/verify/trusteddevice/securitycode",
                headers = authHeaders(pending.clientId),
                body = buildJsonObject {
                    putJsonObject("securityCode") { put("code", code) }
                }.toString(),
            ),
        )
        if (verification.code !in 200..299) throw invalidTwoFactorCode()
        return true
    }

    private suspend fun resendTwoFactorInternal() {
        val pending = requireNotNull(pendingAuth) { "No Apple verification challenge is active" }
        when (pending.route) {
            PendingTwoFactorRoute.LEGACY_TRUSTED_DEVICE -> {
                val request = AppleHttpRequest(
                    method = "GET",
                    url = "$AUTH_ENDPOINT/verify/trusteddevice",
                    headers = authHeaders(pending.clientId),
                )
                if (transport.execute(request).code !in 200..299) throw resendFailed()
            }

            PendingTwoFactorRoute.TRUSTED_DEVICE_BRIDGE -> {
                trustedDeviceBridge.close(pending.bridgeState)
                val context = pending.bootContext ?: throw resendFailed()
                val state = try {
                    trustedDeviceBridge.start(context, authHeaders(pending.clientId))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    pendingAuth = pending.copy(bridgeState = null)
                    throw AppleProtocolException(
                        AppleProtocolError.UNSUPPORTED_TWO_FACTOR,
                        "Apple could not open another trusted-device prompt.",
                        error,
                    )
                }
                pendingAuth = pending.copy(bridgeState = state)
            }

            PendingTwoFactorRoute.SMS -> {
                val request = AppleHttpRequest(
                    method = "PUT",
                    url = "$AUTH_ENDPOINT/verify/phone",
                    headers = authHeaders(pending.clientId),
                    body = buildJsonObject {
                        putJsonObject("phoneNumber") {
                            put("id", pending.phoneId ?: error("Missing trusted phone id"))
                        }
                        put("mode", "sms")
                    }.toString(),
                )
                if (transport.execute(request).code !in 200..299) throw resendFailed()
            }
        }
    }

    private suspend fun restoreInternal(snapshot: AppleSessionSnapshot): AppleSessionSnapshot? {
        val started = System.nanoTime()
        transport.restore(snapshot)
        val response = try {
            transport.execute(
                AppleHttpRequest(
                    method = "POST",
                    url = setupUrl("validate", snapshot.clientId, snapshot.dsid),
                    headers = JSON_HEADERS,
                    body = "null",
                ),
            )
        } catch (error: Throwable) {
            telemetry.record(
                AccountOperation.VALIDATE,
                error.accountOutcome(),
                elapsedSince(started),
                snapshot.cookieLifetimeMetrics(),
            )
            throw error
        }
        // A stale validation cookie does not necessarily mean Apple's longer-lived
        // session and trust tokens have expired. Match iCloud.com's token login before
        // asking the person for their password and another verification code.
        if (response.code == 401 || response.code == 403) {
            telemetry.record(
                AccountOperation.VALIDATE,
                AccountOutcome.EXPIRED,
                elapsedSince(started),
                snapshot.cookieLifetimeMetrics(),
            )
            return renewSession(snapshot)
        }
        check(response.code in 200..299) { "iCloud validation is temporarily unavailable (${response.code})" }
        val restored = snapshotFromAccountResponse(
            appleId = snapshot.accountName,
            clientId = snapshot.clientId,
            response = response,
            fallback = snapshot,
        )
        telemetry.record(
            AccountOperation.VALIDATE,
            AccountOutcome.SUCCESS,
            elapsedSince(started),
            restored.cookieLifetimeMetrics(),
        )
        return restored
    }

    private suspend fun renewSession(snapshot: AppleSessionSnapshot): AppleSessionSnapshot? {
        val started = System.nanoTime()
        val response = try {
            accountLogin(snapshot.clientId)
        } catch (error: Throwable) {
            telemetry.record(
                AccountOperation.RENEW,
                error.accountOutcome(),
                elapsedSince(started),
                snapshot.cookieLifetimeMetrics(),
            )
            throw error
        }
        if (response.code == 401 || response.code == 403) {
            telemetry.record(
                AccountOperation.RENEW,
                AccountOutcome.EXPIRED,
                elapsedSince(started),
                snapshot.cookieLifetimeMetrics(),
            )
            return null
        }
        check(response.code in 200..299) {
            "iCloud token renewal is temporarily unavailable (${response.code})"
        }
        return try {
            snapshotFromAccountResponse(
                appleId = snapshot.accountName,
                clientId = snapshot.clientId,
                response = response,
                fallback = snapshot,
            ).also {
                telemetry.record(
                    AccountOperation.RENEW,
                    AccountOutcome.SUCCESS,
                    elapsedSince(started),
                    it.cookieLifetimeMetrics(),
                )
            }
        } catch (error: AppleProtocolException) {
            telemetry.record(
                AccountOperation.RENEW,
                error.accountOutcome(),
                elapsedSince(started),
                snapshot.cookieLifetimeMetrics(),
            )
            if (error.error == AppleProtocolError.SESSION_EXPIRED) null else throw error
        }
    }

    suspend fun logout(snapshot: AppleSessionSnapshot) {
        transport.restore(snapshot)
        try {
            transport.execute(
                AppleHttpRequest(
                    method = "POST",
                    url = setupUrl("logout", snapshot.clientId, snapshot.dsid),
                    headers = mapOf("Content-Type" to "text/plain;charset=UTF-8"),
                    body = buildJsonObject {
                        put("trustBrowser", false)
                        put("allBrowsers", false)
                    }.toString(),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The local session has already been removed; remote logout is best effort.
        } finally {
            clearPendingAuth()
            transport.clear()
        }
    }

    fun cancel() {
        clearPendingAuth()
        transport.clear()
    }

    private suspend fun authorizeSignInShell(clientId: String) {
        val headers = authHeaders(clientId)
        val url = "$AUTH_ENDPOINT/authorize/signin".toHttpUrl().newBuilder()
            .addQueryParameter("frame_id", clientId)
            .addQueryParameter("skVersion", "7")
            .addQueryParameter("iframeid", clientId)
            .addQueryParameter("client_id", WIDGET_KEY)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", ICLOUD_HOME)
            .addQueryParameter("response_mode", "web_message")
            .addQueryParameter("state", clientId)
            .addQueryParameter("authVersion", "latest")
            .build()
            .toString()
        val response = transport.execute(AppleHttpRequest("GET", url, headers))
        if (response.code !in 200..399) throw invalidCredentials()
    }

    private suspend fun beginSrp(
        appleId: String,
        clientId: String,
        publicA: ByteArray,
    ): JsonObject {
        val response = transport.execute(
            AppleHttpRequest(
                method = "POST",
                url = "$AUTH_ENDPOINT/signin/init",
                headers = authHeaders(clientId),
                body = buildJsonObject {
                    put("a", encodeBase64(publicA))
                    put("accountName", appleId)
                    putJsonArray("protocols") {
                        AppleSrpProtocol.entries.forEach { add(JsonPrimitive(it.wireValue)) }
                    }
                }.toString(),
            ),
        )
        if (response.code !in 200..299) throw invalidCredentials()
        return response.requireJsonObject("Apple returned an invalid SRP challenge")
    }

    private suspend fun completeSrp(
        appleId: String,
        clientId: String,
        challengeId: String,
        proof: AppleSrpProof,
    ): AppleHttpResponse {
        val trustToken = transport.sessionHeaders.trustToken
        return transport.execute(
            AppleHttpRequest(
                method = "POST",
                url = "$AUTH_ENDPOINT/signin/complete?isRememberMeEnabled=true",
                headers = authHeaders(clientId),
                body = buildJsonObject {
                    put("accountName", appleId)
                    put("c", challengeId)
                    put("m1", encodeBase64(proof.clientProof))
                    put("m2", encodeBase64(proof.expectedServerProof))
                    put("rememberMe", true)
                    putJsonArray("trustTokens") {
                        trustToken?.let { add(JsonPrimitive(it)) }
                    }
                }.toString(),
            ),
        )
    }

    private suspend fun requestTwoFactor(appleId: String, clientId: String): PendingAuth {
        val optionsResponse = try {
            transport.execute(
                AppleHttpRequest(
                    method = "GET",
                    url = AUTH_ENDPOINT,
                    headers = authHeaders(clientId) + ("Accept" to "text/html"),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val bootContext = optionsResponse
            ?.takeIf { it.code in 200..299 }
            ?.body
            ?.let(Hsa2BootContext::parse)
        if (bootContext?.supportsTrustedDeviceBridge == true) {
            val bridgeState = try {
                trustedDeviceBridge.start(bootContext, authHeaders(clientId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (bridgeState != null) {
                return PendingAuth(
                    appleId = appleId,
                    clientId = clientId,
                    delivery = TwoFactorDelivery.TRUSTED_DEVICE,
                    route = PendingTwoFactorRoute.TRUSTED_DEVICE_BRIDGE,
                    bootContext = bootContext,
                    bridgeState = bridgeState,
                )
            }
        }

        val trustedDevice = transport.execute(
            AppleHttpRequest(
                method = "GET",
                url = "$AUTH_ENDPOINT/verify/trusteddevice",
                headers = authHeaders(clientId),
            ),
        )
        if (trustedDevice.code in 200..299) {
            return PendingAuth(
                appleId = appleId,
                clientId = clientId,
                delivery = TwoFactorDelivery.TRUSTED_DEVICE,
                route = PendingTwoFactorRoute.LEGACY_TRUSTED_DEVICE,
            )
        }

        val authOptions = optionsResponse?.jsonObjectOrNull()
        val phone = bootContext?.phoneNumberVerification.findTrustedPhone()
            ?: authOptions.findTrustedPhone()
            ?: throw AppleProtocolException(
                AppleProtocolError.UNSUPPORTED_TWO_FACTOR,
                "Apple did not offer a trusted-device or SMS verification route.",
            )
        val sms = transport.execute(
            AppleHttpRequest(
                method = "PUT",
                url = "$AUTH_ENDPOINT/verify/phone",
                headers = authHeaders(clientId),
                body = buildJsonObject {
                    putJsonObject("phoneNumber") { put("id", phone.id) }
                    put("mode", "sms")
                }.toString(),
            ),
        )
        if (sms.code !in 200..299) {
            throw AppleProtocolException(
                AppleProtocolError.UNSUPPORTED_TWO_FACTOR,
                "Apple could not send a verification code.",
            )
        }
        return PendingAuth(
            appleId = appleId,
            clientId = clientId,
            delivery = TwoFactorDelivery.SMS,
            route = PendingTwoFactorRoute.SMS,
            phoneId = phone.id,
            destinationHint = phone.hint,
        )
    }

    private suspend fun establishSession(appleId: String, clientId: String): AppleSessionSnapshot {
        val response = accountLogin(clientId)
        if (response.code !in 200..299) {
            throw AppleProtocolException(
                AppleProtocolError.SESSION_EXPIRED,
                "Apple did not establish the iCloud web session.",
            )
        }
        return snapshotFromAccountResponse(appleId, clientId, response)
    }

    private suspend fun accountLogin(clientId: String): AppleHttpResponse {
        val headers = transport.sessionHeaders
        val country = headers.accountCountryCode
            ?: throw malformed("Apple did not return an account country")
        val sessionToken = headers.sessionToken
            ?: throw malformed("Apple did not return a session token")
        return transport.execute(
            AppleHttpRequest(
                method = "POST",
                url = setupUrl("accountLogin", clientId),
                headers = JSON_HEADERS,
                body = buildJsonObject {
                    put("accountCountryCode", country)
                    put("dsWebAuthToken", sessionToken)
                    put("extended_login", true)
                    put("trustToken", headers.trustToken.orEmpty())
                }.toString(),
            ),
        )
    }

    private fun snapshotFromAccountResponse(
        appleId: String,
        clientId: String,
        response: AppleHttpResponse,
        fallback: AppleSessionSnapshot? = null,
    ): AppleSessionSnapshot {
        val root = response.requireJsonObject("Apple returned an invalid account response")
        if ((root["termsUpdateNeeded"] as? JsonPrimitive)?.booleanOrNull == true) {
            throw AppleProtocolException(
                AppleProtocolError.TERMS_UPDATE_REQUIRED,
                "Open iCloud.com and accept Apple's updated terms, then try again.",
            )
        }
        val dsInfo = root["dsInfo"] as? JsonObject
        val dsid = (dsInfo?.get("dsid") as? JsonPrimitive)?.contentOrNull ?: fallback?.dsid
            ?: throw malformed("Apple did not return an account identifier")
        val trusted = (dsInfo?.get("hsaVersion") as? JsonPrimitive)?.intOrNull != 2 ||
            (root["hsaTrustedBrowser"] as? JsonPrimitive)?.booleanOrNull == true
        if (!trusted) {
            throw AppleProtocolException(
                AppleProtocolError.SESSION_EXPIRED,
                "The Apple session still requires verification.",
            )
        }
        val discoveredServices = root["webservices"] as? JsonObject
        val services = DISCOVERED_SERVICES.associateWithNotNull { serviceName ->
            val discoveredUrl = (discoveredServices?.get(serviceName) as? JsonObject)
                ?.get("url")
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull
            // A fresh inventory is authoritative: do not resurrect withdrawn services.
            val url = if (discoveredServices != null) discoveredUrl else fallback?.webservices?.get(serviceName)
            url?.also(AppleEndpointPolicy::requireAllowed)?.trimEnd('/')
        }
        services.values.forEach(AppleEndpointPolicy::requireAllowed)
        val sessionHeaders = transport.sessionHeaders
        return AppleSessionSnapshot(
            accountName = appleId,
            displayName = (dsInfo?.get("fullName") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: listOfNotNull("firstName", "lastName").mapNotNull { key ->
                    (dsInfo?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                }.joinToString(" ").takeIf { it.isNotBlank() }
                ?: fallback?.takeIf { it.dsid == dsid }?.displayName,
            clientId = clientId,
            accountCountryCode = sessionHeaders.accountCountryCode ?: fallback?.accountCountryCode
                ?: throw malformed("Apple did not return an account country"),
            sessionId = sessionHeaders.sessionId ?: fallback?.sessionId
                ?: throw malformed("Apple did not return a session identifier"),
            sessionToken = sessionHeaders.sessionToken ?: fallback?.sessionToken
                ?: throw malformed("Apple did not return a session token"),
            trustToken = sessionHeaders.trustToken ?: fallback?.trustToken,
            dsid = dsid,
            webservices = services,
            cookies = transport.snapshotCookies(),
        )
    }

    private fun authHeaders(clientId: String): Map<String, String> = buildMap {
        putAll(AUTH_HEADERS)
        put("Referer", IDMS_ENDPOINT)
        put("X-Apple-OAuth-State", clientId)
        put("X-Apple-Frame-Id", clientId)
        transport.sessionHeaders.scnt?.let { put("scnt", it) }
        transport.sessionHeaders.sessionId?.let { put("X-Apple-ID-Session-Id", it) }
        transport.sessionHeaders.authAttributes?.let { put("X-Apple-Auth-Attributes", it) }
    }

    private inline fun <K, V : Any> Iterable<K>.associateWithNotNull(transform: (K) -> V?): Map<K, V> =
        buildMap {
            this@associateWithNotNull.forEach { key -> transform(key)?.let { value -> put(key, value) } }
        }

    private fun setupUrl(path: String, clientId: String, dsid: String? = null): String =
        "$SETUP_ENDPOINT/$path".toHttpUrl().newBuilder()
            .addQueryParameter("clientBuildNumber", CLIENT_BUILD)
            .addQueryParameter("clientMasteringNumber", CLIENT_MASTERING)
            .addQueryParameter("clientId", clientId)
            .apply { dsid?.let { addQueryParameter("dsid", it) } }
            .build()
            .toString()

    private fun AppleHttpResponse.requireJsonObject(message: String): JsonObject = jsonObjectOrNull()
        ?: throw malformed(message)

    private fun AppleHttpResponse.jsonObjectOrNull(): JsonObject? = runCatching {
        json.parseToJsonElement(body).jsonObject
    }.getOrNull()

    private fun JsonObject.requiredString(name: String): String = this[name]
        ?.let { it as? JsonPrimitive }
        ?.contentOrNull
        ?: throw malformed("Apple's authentication response is missing $name")

    private fun JsonObject.requiredInt(name: String): Int = this[name]
        ?.let { it as? JsonPrimitive }
        ?.intOrNull
        ?: throw malformed("Apple's authentication response is missing $name")

    private fun JsonObject?.findTrustedPhone(): TrustedPhone? {
        if (this == null) return null
        val direct = this["trustedPhoneNumber"] as? JsonObject
        val nested = this["phoneNumberVerification"]
            ?.let { it as? JsonObject }
            ?.get("trustedPhoneNumber")
            ?.let { it as? JsonObject }
        val phone = direct ?: nested ?: return null
        val id = (phone["id"] as? JsonPrimitive)?.contentOrNull ?: return null
        val hint = (phone["numberWithDialCode"] as? JsonPrimitive)?.contentOrNull
            ?: (phone["obfuscatedNumber"] as? JsonPrimitive)?.contentOrNull
        return TrustedPhone(id = id, hint = hint)
    }

    private fun encodeBase64(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

    private fun decodeBase64(value: String): ByteArray = runCatching {
        Base64.getDecoder().decode(value)
    }.getOrElse { throw malformed("Apple returned invalid authentication bytes") }

    private fun invalidCredentials() = AppleProtocolException(
        AppleProtocolError.INVALID_CREDENTIALS,
        "Apple rejected the account name or password.",
    )

    private fun invalidTwoFactorCode() = AppleProtocolException(
        AppleProtocolError.INVALID_TWO_FACTOR_CODE,
        "Apple rejected that verification code.",
    )

    private fun resendFailed() = AppleProtocolException(
        AppleProtocolError.UNSUPPORTED_TWO_FACTOR,
        "Apple could not send another verification code.",
    )

    private fun clearPendingAuth() {
        pendingAuth?.bridgeState?.let(trustedDeviceBridge::close)
        pendingAuth = null
    }

    private fun malformed(message: String) = AppleProtocolException(
        AppleProtocolError.MALFORMED_RESPONSE,
        message,
    )

    private data class PendingAuth(
        val appleId: String,
        val clientId: String,
        val delivery: TwoFactorDelivery,
        val route: PendingTwoFactorRoute,
        val phoneId: String? = null,
        val destinationHint: String? = null,
        val bootContext: Hsa2BootContext? = null,
        val bridgeState: TrustedDeviceBridgeState? = null,
    )

    private enum class PendingTwoFactorRoute {
        LEGACY_TRUSTED_DEVICE,
        TRUSTED_DEVICE_BRIDGE,
        SMS,
    }

    private data class TrustedPhone(
        val id: String,
        val hint: String?,
    )

    private companion object {
        val DISCOVERED_SERVICES = listOf("ckdatabasews", "photosupload", "account", "contacts")
        const val HTTP_TWO_FACTOR_REQUIRED = 409
        const val IDMS_ENDPOINT = "https://idmsa.apple.com"
        const val AUTH_ENDPOINT = "$IDMS_ENDPOINT/appleauth/auth"
        const val ICLOUD_HOME = "https://www.icloud.com"
        const val SETUP_ENDPOINT = "https://setup.icloud.com/setup/ws/1"
        const val WIDGET_KEY = "d39ba9916b7251055b22c7f910e2ea796ee65e98b2ddecea8f5dde8d9d1a815d"
        const val CLIENT_BUILD = "2534Project66"
        const val CLIENT_MASTERING = "2534B22"
        val JSON_HEADERS = mapOf("Content-Type" to "application/json")
        val AUTH_HEADERS = mapOf(
            "Accept" to "application/json, text/javascript",
            "Content-Type" to "application/json",
            "X-Apple-OAuth-Client-Id" to WIDGET_KEY,
            "X-Apple-OAuth-Client-Type" to "firstPartyAuth",
            "X-Apple-OAuth-Redirect-URI" to ICLOUD_HOME,
            "X-Apple-OAuth-Require-Grant-Code" to "true",
            "X-Apple-OAuth-Response-Mode" to "web_message",
            "X-Apple-OAuth-Response-Type" to "code",
            "X-Apple-Widget-Key" to WIDGET_KEY,
            "X-Apple-FD-Client-Info" to AppleClientProfile.FD_CLIENT_INFO,
        )
    }
}
