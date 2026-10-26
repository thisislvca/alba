package me.lvca.alba.protocol.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.lvca.alba.protocol.account.AccountTelemetry
import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.account.AppleSignInResult
import me.lvca.alba.protocol.account.TwoFactorDelivery
import me.lvca.alba.protocol.int
import me.lvca.alba.protocol.network.AppleClientProfile
import me.lvca.alba.protocol.network.AppleHttpRequest
import me.lvca.alba.protocol.network.AppleHttpResponse
import me.lvca.alba.protocol.network.AppleHttpTransport
import me.lvca.alba.protocol.network.AppleSessionHeaderTracker
import me.lvca.alba.protocol.text
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Base64
import java.util.UUID

class AppleAuthenticationService internal constructor(
    transport: AppleHttpTransport,
    private val clientIdProvider: () -> String,
    trustedDeviceBridge: (AppleHttpTransport) -> TrustedDeviceBridgeClient,
    telemetry: AccountTelemetry = AccountTelemetry.None,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    constructor(
        transport: AppleHttpTransport,
        clientIdProvider: () -> String = { UUID.randomUUID().toString().lowercase() },
        telemetry: AccountTelemetry = AccountTelemetry.None,
        computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(transport, clientIdProvider, { TrustedDeviceBridge(it) }, telemetry, computationDispatcher)

    // Sign-in reads scnt and session tokens from its own responses, including the bridge's,
    // never from another client sharing the transport.
    private val transport = AppleSessionHeaderTracker(transport)
    private val trustedDeviceBridge = trustedDeviceBridge(this.transport)
    private val webSession = ICloudWebSession(this.transport, telemetry)
    private val json = Json { ignoreUnknownKeys = true }
    private var pendingAuth: PendingAuth? = null

    suspend fun signIn(appleId: String, password: String, reusableSession: AppleSessionSnapshot? = null): AppleSignInResult =
        withContext(computationDispatcher) { signInInternal(appleId, password, reusableSession) }

    suspend fun submitTwoFactor(code: String): AppleSessionSnapshot =
        withContext(computationDispatcher) { submitTwoFactorInternal(code) }

    suspend fun resendTwoFactor() = withContext(computationDispatcher) { resendTwoFactorInternal() }

    /**
     * Switches the pending verification to a text message to the trusted phone number Apple offered with the
     * challenge, using the same `verify/phone` request as the SMS route. Only valid when the last result said
     * [AppleSignInResult.RequiresTwoFactor.smsAvailable].
     */
    suspend fun sendCodeBySms(): AppleSignInResult.RequiresTwoFactor =
        withContext(computationDispatcher) { sendCodeBySmsInternal() }

    suspend fun restore(snapshot: AppleSessionSnapshot): AppleSessionSnapshot? =
        withContext(computationDispatcher) { webSession.restore(snapshot) }

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

        completion.requireNotThrottled()
        if (completion.code == HTTP_TWO_FACTOR_REQUIRED) {
            val completionBody = completion.jsonObjectOrNull()
            // Accounts protected by hardware security keys answer with a FIDO challenge and the keys' names instead
            // of a code route; the web sign-in Alba uses cannot complete it.
            completionBody.requireNoSecurityKey()
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
                smsAvailable = pending.offersSms,
            )
        }
        if (completion.code !in 200..299) throw invalidCredentials()

        val session = webSession.establish(normalizedAppleId, clientId)
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
                if (verification.code == HTTP_TOO_MANY_REQUESTS) throw tooManyAttempts()
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

        val session = webSession.establish(pending.appleId, pending.clientId)
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
        if (verification.code == HTTP_TOO_MANY_REQUESTS) throw tooManyAttempts()
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

    private suspend fun sendCodeBySmsInternal(): AppleSignInResult.RequiresTwoFactor {
        val pending = requireNotNull(pendingAuth) { "No Apple verification challenge is active" }
        val phone = pending.smsPhone ?: throw AppleProtocolException(
            AppleProtocolError.UNSUPPORTED_TWO_FACTOR,
            "Apple did not offer a trusted phone number for this account.",
        )
        val sms = transport.execute(smsRequest(pending.clientId, phone.id))
        sms.requireNotThrottled()
        if (sms.code !in 200..299) throw resendFailed()
        trustedDeviceBridge.close(pending.bridgeState)
        pendingAuth = pending.copy(
            delivery = TwoFactorDelivery.SMS,
            route = PendingTwoFactorRoute.SMS,
            phoneId = phone.id,
            destinationHint = phone.hint,
            bridgeState = null,
        )
        return AppleSignInResult.RequiresTwoFactor(pending.appleId, TwoFactorDelivery.SMS, phone.hint)
    }

    private fun smsRequest(clientId: String, phoneId: String) = AppleHttpRequest(
        method = "PUT",
        url = "$AUTH_ENDPOINT/verify/phone",
        headers = authHeaders(clientId),
        body = buildJsonObject {
            putJsonObject("phoneNumber") { put("id", phoneId) }
            put("mode", "sms")
        }.toString(),
    )

    suspend fun logout(snapshot: AppleSessionSnapshot) {
        try {
            webSession.logout(snapshot)
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
        if (response.code == HTTP_TOO_MANY_REQUESTS) throw tooManyAttempts()
        if (response.code !in 200..299) throw invalidCredentials()
        return response.requireJsonObject("Apple returned an invalid SRP challenge")
    }

    private suspend fun completeSrp(
        appleId: String,
        clientId: String,
        challengeId: String,
        proof: AppleSrpProof,
    ): AppleHttpResponse {
        val trustToken = transport.headers.trustToken
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
        val authOptions = optionsResponse?.jsonObjectOrNull()
        authOptions.requireNoSecurityKey()
        val trustedPhone = bootContext?.phoneNumberVerification.findTrustedPhone()
            ?: authOptions.findTrustedPhone()
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
                    smsPhone = trustedPhone,
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
                smsPhone = trustedPhone,
            )
        }

        val phone = trustedPhone
            ?: throw AppleProtocolException(
                AppleProtocolError.UNSUPPORTED_TWO_FACTOR,
                "Apple did not offer a trusted-device or SMS verification route.",
            )
        val sms = transport.execute(smsRequest(clientId, phone.id))
        if (sms.code == HTTP_TOO_MANY_REQUESTS) throw tooManyAttempts()
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

    private fun authHeaders(clientId: String): Map<String, String> = buildMap {
        putAll(AUTH_HEADERS)
        put("Referer", IDMS_ENDPOINT)
        put("X-Apple-OAuth-State", clientId)
        put("X-Apple-Frame-Id", clientId)
        transport.headers.scnt?.let { put("scnt", it) }
        transport.headers.sessionId?.let { put("X-Apple-ID-Session-Id", it) }
        transport.headers.authAttributes?.let { put("X-Apple-Auth-Attributes", it) }
    }

    private fun AppleHttpResponse.requireJsonObject(message: String): JsonObject = jsonObjectOrNull()
        ?: throw malformed(message)

    private fun AppleHttpResponse.jsonObjectOrNull(): JsonObject? = runCatching {
        json.parseToJsonElement(body).jsonObject
    }.getOrNull()

    private fun JsonObject.requiredString(name: String): String = text(name)
        ?: throw malformed("Apple's authentication response is missing $name")

    private fun JsonObject.requiredInt(name: String): Int = int(name)
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

    /** Refuses accounts Apple answers with a FIDO challenge for hardware security keys (`fsaChallenge`, `keyNames`). */
    private fun JsonObject?.requireNoSecurityKey() {
        if (this == null) return
        val challenge = this["fsaChallenge"]
        if ((challenge != null && challenge !is JsonNull) || (this["keyNames"] as? JsonArray)?.isNotEmpty() == true) {
            throw securityKeyRequired()
        }
    }

    /** Apple answers too many recent attempts with HTTP 429, which is not a wrong password or code. */
    private fun AppleHttpResponse.requireNotThrottled() {
        if (code == HTTP_TOO_MANY_REQUESTS) throw tooManyAttempts()
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

    private fun tooManyAttempts() = AppleProtocolException(
        AppleProtocolError.TOO_MANY_ATTEMPTS,
        "Apple refused the attempt because of too many recent tries.",
    )

    private fun securityKeyRequired() = AppleProtocolException(
        AppleProtocolError.SECURITY_KEY_REQUIRED,
        "This Apple Account requires a security key to sign in on the web.",
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
        /** The trusted number a code can be texted to instead, when Apple offered one. */
        val smsPhone: TrustedPhone? = null,
    ) {
        val offersSms: Boolean get() = route != PendingTwoFactorRoute.SMS && smsPhone != null
    }

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
        const val HTTP_TWO_FACTOR_REQUIRED = 409
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val IDMS_ENDPOINT = "https://idmsa.apple.com"
        const val AUTH_ENDPOINT = "$IDMS_ENDPOINT/appleauth/auth"
        const val ICLOUD_HOME = "https://www.icloud.com"
        const val WIDGET_KEY = "d39ba9916b7251055b22c7f910e2ea796ee65e98b2ddecea8f5dde8d9d1a815d"
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
