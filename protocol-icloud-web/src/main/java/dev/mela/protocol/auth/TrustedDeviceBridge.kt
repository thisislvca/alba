package dev.mela.protocol.auth

import dev.mela.protocol.network.AppleEndpointPolicy
import dev.mela.protocol.network.AppleHttpRequest
import dev.mela.protocol.network.AppleHttpTransport
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal data class TrustedDeviceBridgeState(
    val connectionPath: String,
    val pushToken: String,
    val sessionId: String,
    var socket: BridgeSocket?,
    val topic: String,
    val sourceAppId: String?,
    var nextStep: String? = null,
    var transactionId: String? = null,
    var salt: String? = null,
    var machineId: String? = null,
    var idmsData: String? = null,
    var akData: JsonElement? = null,
    var data: String? = null,
    var encryptedCode: String? = null,
    var errorCode: Int? = null,
) {
    val usesLegacyVerifier: Boolean get() = transactionId?.endsWith("_W") == true

    fun apply(payload: BridgePushPayload) {
        require(payload.sessionId == sessionId) { "Bridge push belongs to another session" }
        nextStep = payload.nextStep
        transactionId = payload.transactionId
        salt = payload.salt
        machineId = payload.machineId
        idmsData = payload.idmsData
        akData = payload.akData
        data = payload.data
        encryptedCode = payload.encryptedCode
        errorCode = payload.errorCode
    }
}

internal interface TrustedDeviceBridgeClient {
    suspend fun start(context: Hsa2BootContext, headers: Map<String, String>): TrustedDeviceBridgeState

    suspend fun validateCode(
        state: TrustedDeviceBridgeState,
        headers: Map<String, String>,
        code: String,
    ): Boolean

    fun close(state: TrustedDeviceBridgeState?)
}

internal class TrustedDeviceBridge(
    private val transport: AppleHttpTransport,
    private val socketFactory: BridgeSocketFactory = OkHttpBridgeSocketFactory(),
    private val proverFactory: () -> BridgeProver = ::TrustedDeviceBridgeProver,
    private val keyMaterialProvider: () -> BridgeKeyMaterial = ::generateKeyMaterial,
    private val sessionIdProvider: () -> String = {
        "${UUID.randomUUID()}-${Clock.systemUTC().instant().epochSecond}"
    },
    private val nowMillis: () -> Long = { Clock.systemUTC().millis() },
    private val secureRandom: SecureRandom = SecureRandom(),
    private val timeoutMillis: Long = BRIDGE_TIMEOUT_MILLIS,
) : TrustedDeviceBridgeClient {
    override suspend fun start(
        context: Hsa2BootContext,
        headers: Map<String, String>,
    ): TrustedDeviceBridgeState {
        require(context.supportsTrustedDeviceBridge) { "Apple did not offer a trusted-device bridge" }
        val host = resolveWebSocketHost(context)
        val topic = resolveTopic(context)
        val keyMaterial = keyMaterialProvider()
        var timestampMillis: Long? = null
        var lastError: Throwable? = null

        bridgeAttempts@ for (attempt in 0 until 2) {
            val nonce = buildNonce(timestampMillis ?: nowMillis())
            val connection = TrustedDeviceBridgeWire.encodeConnection(
                publicKey = keyMaterial.publicKey,
                nonce = nonce,
                signature = keyMaterial.sign(nonce),
            )
            val connectionPath = connection.toHex()
            var socket: BridgeSocket? = null
            var keepOpen = false
            try {
                socket = socketFactory.open(
                    url = "wss://$host/v2/$connectionPath",
                    origin = AUTH_ENDPOINT_ORIGIN,
                )
                val pushToken = waitForPushToken(socket)
                val pushTokenHex = pushToken.toHex()
                socket.send(TrustedDeviceBridgeWire.encodeTopicFilter(topic))
                val sessionId = sessionIdProvider()
                postStepZero(
                    headers = bridgeHeaders(headers, context.sourceAppId),
                    sessionId = sessionId,
                    pushToken = pushTokenHex,
                )
                val initialPush = waitForPush(socket, topic)
                if (initialPush.hasExplicitSessionId && initialPush.sessionId != sessionId) {
                    throw BridgePromptException("Apple returned a mismatched bridge session")
                }
                val state = TrustedDeviceBridgeState(
                    connectionPath = connectionPath,
                    pushToken = pushTokenHex,
                    sessionId = initialPush.sessionId,
                    socket = socket,
                    topic = topic,
                    sourceAppId = context.sourceAppId,
                )
                applyPush(state, initialPush)
                keepOpen = true
                return state
            } catch (invalidNonce: InvalidBridgeNonceException) {
                timestampMillis = Math.multiplyExact(invalidNonce.serverTimestampSeconds, 1_000L)
                lastError = invalidNonce
                if (attempt == 1) break@bridgeAttempts
            } catch (timeout: TimeoutCancellationException) {
                lastError = timeout
                break@bridgeAttempts
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                break@bridgeAttempts
            } finally {
                if (!keepOpen) socket?.close()
            }
        }
        throw BridgePromptException("Apple's trusted-device prompt did not start", lastError)
    }

    override suspend fun validateCode(
        state: TrustedDeviceBridgeState,
        headers: Map<String, String>,
        code: String,
    ): Boolean {
        val socket = state.socket ?: throw BridgeVerificationException("The Apple verification prompt expired")
        require(!state.usesLegacyVerifier) { "Legacy trusted-device prompts use Apple's code endpoint" }
        if (state.nextStep != "2" || state.salt.isNullOrBlank()) {
            throw BridgeVerificationException("Apple's verification prompt is not ready")
        }
        val bridgeHeaders = bridgeHeaders(headers, state.sourceAppId)
        val prover = proverFactory()
        try {
            prover.initialize(requireNotNull(state.salt), code)
            postStep(
                state = state,
                headers = bridgeHeaders,
                step = 2,
                data = hexToBase64(prover.message1()),
            )
            val stepFour = waitForPush(socket, state.topic)
            applyPush(state, stepFour)
            if (state.nextStep != "4" || state.data.isNullOrBlank()) {
                throw BridgeVerificationException("Apple returned an unexpected bridge proof")
            }
            val (serverMessage, serverConfirmation) = decodeStepFour(requireNotNull(state.data))
            val clientConfirmation = try {
                prover.processServerProof(serverMessage, serverConfirmation)
            } catch (_: BridgeProofRejectedException) {
                return false
            } catch (error: IllegalArgumentException) {
                throw BridgeVerificationException("Apple returned a malformed bridge proof", error)
            }
            postStep(
                state = state,
                headers = bridgeHeaders,
                step = 4,
                data = hexToBase64(clientConfirmation),
            )
            val finalPush = waitForPush(socket, state.topic)
            applyPush(state, finalPush)
            if (state.nextStep !in setOf("4", "6") || state.encryptedCode.isNullOrBlank()) {
                throw BridgeVerificationException("Apple returned an incomplete bridge verification")
            }
            val derivedCode = try {
                prover.decryptMessage(requireNotNull(state.encryptedCode))
            } catch (error: IllegalArgumentException) {
                throw BridgeVerificationException("Apple returned an unreadable bridge verification", error)
            }
            val validation = postCodeValidation(state, bridgeHeaders, derivedCode)
            val succeeded = validation != HTTP_PRECONDITION_FAILED
            postStep(
                state = state,
                headers = bridgeHeaders,
                step = if (state.nextStep == "6") 6 else 4,
                data = DONE_DATA_BASE64,
            )
            return succeeded
        } catch (timeout: TimeoutCancellationException) {
            throw BridgeVerificationException("Apple's verification prompt timed out", timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            close(state)
        }
    }

    override fun close(state: TrustedDeviceBridgeState?) {
        val socket = state?.socket ?: return
        state.socket = null
        socket.close()
    }

    private suspend fun waitForPushToken(socket: BridgeSocket): ByteArray = withTimeout(timeoutMillis) {
        while (true) {
            val connection = TrustedDeviceBridgeWire.decodeServerMessage(socket.readMessage()).connection
                ?: continue
            when {
                connection.status == STATUS_OK && connection.pushTokenBase64.isNotBlank() -> {
                    return@withTimeout runCatching {
                        Base64.getDecoder().decode(connection.pushTokenBase64)
                    }.getOrElse { throw BridgePromptException("Apple returned a malformed push token", it) }
                }

                connection.status == STATUS_INVALID_NONCE && connection.serverTimestampSeconds != null -> {
                    throw InvalidBridgeNonceException(connection.serverTimestampSeconds)
                }

                else -> throw BridgePromptException(
                    "Apple's bridge connection failed with status ${connection.status}",
                )
            }
        }
        error("Unreachable")
    }

    private suspend fun waitForPush(socket: BridgeSocket, topic: String): BridgePushPayload =
        withTimeout(timeoutMillis) {
            while (true) {
                val message = TrustedDeviceBridgeWire.decodeServerMessage(socket.readMessage())
                message.subscriptionStatus?.let { status ->
                    if (status != STATUS_OK) {
                        throw BridgePromptException("Apple rejected the bridge topic subscription")
                    }
                }
                val push = message.push ?: continue
                socket.send(TrustedDeviceBridgeWire.encodeAcknowledgement(push.topic, push.messageId))
                if (TrustedDeviceBridgeWire.topicName(push.topic, topic) != topic) continue
                return@withTimeout TrustedDeviceBridgeWire.decodePushPayload(push.payload)
            }
            error("Unreachable")
        }

    private fun applyPush(state: TrustedDeviceBridgeState, payload: BridgePushPayload) {
        if (payload.sessionId != state.sessionId) {
            throw BridgeVerificationException("Apple returned a bridge push for another session")
        }
        if (payload.errorCode != null && payload.errorCode != 0) {
            throw BridgeVerificationException("Apple rejected the bridge verification")
        }
        state.apply(payload)
    }

    private suspend fun postStepZero(
        headers: Map<String, String>,
        sessionId: String,
        pushToken: String,
    ) {
        val response = transport.execute(
            AppleHttpRequest(
                method = "POST",
                url = "$AUTH_ENDPOINT/bridge/step/0",
                headers = headers,
                body = buildJsonObject {
                    put("sessionUUID", sessionId)
                    put("ptkn", pushToken)
                }.toString(),
            ),
        )
        if (response.code !in VALID_STEP_STATUSES) {
            throw BridgePromptException("Apple rejected bridge step 0")
        }
    }

    private suspend fun postStep(
        state: TrustedDeviceBridgeState,
        headers: Map<String, String>,
        step: Int,
        data: String,
    ) {
        val response = transport.execute(
            AppleHttpRequest(
                method = "POST",
                url = "$AUTH_ENDPOINT/bridge/step/$step",
                headers = headers,
                body = buildJsonObject {
                    put("sessionUUID", state.sessionId)
                    put("data", data)
                    put("ptkn", state.pushToken)
                    put("nextStep", step)
                    state.idmsData?.let { put("idmsdata", it) }
                    state.akData?.let { akData ->
                        if (akData is JsonObject) put("akdata", akData.toString()) else put("akdata", akData)
                    }
                }.toString(),
            ),
        )
        if (response.code !in VALID_STEP_STATUSES) {
            throw BridgeVerificationException("Apple rejected bridge step $step")
        }
    }

    private suspend fun postCodeValidation(
        state: TrustedDeviceBridgeState,
        headers: Map<String, String>,
        code: String,
    ): Int {
        val response = transport.execute(
            AppleHttpRequest(
                method = "POST",
                url = "$AUTH_ENDPOINT/bridge/code/validate",
                headers = headers,
                body = buildJsonObject {
                    put("sessionUUID", state.sessionId)
                    put("code", code)
                }.toString(),
            ),
        )
        if (response.code !in VALID_CODE_STATUSES) {
            throw BridgeVerificationException("Apple rejected bridge code validation")
        }
        return response.code
    }

    private fun decodeStepFour(data: String): Pair<String, String> = runCatching {
        val decoded = Base64.getDecoder().decode(data).toString(Charsets.UTF_8)
        val separator = decoded.indexOf('_')
        require(separator > 0 && separator < decoded.lastIndex)
        val serverMessage = Base64.getDecoder().decode(decoded.substring(0, separator)).toHex()
        val serverConfirmation = Base64.getDecoder().decode(decoded.substring(separator + 1)).toHex()
        serverMessage to serverConfirmation
    }.getOrElse { throw BridgeVerificationException("Apple returned malformed bridge proof data", it) }

    private fun hexToBase64(value: String): String =
        Base64.getEncoder().encodeToString(value.hexToBytes())

    private fun bridgeHeaders(headers: Map<String, String>, sourceAppId: String?): Map<String, String> =
        if (sourceAppId == null) headers else headers + ("X-Apple-App-Id" to sourceAppId)

    private fun buildNonce(timestampMillis: Long): ByteArray {
        require(timestampMillis >= 0) { "Invalid bridge timestamp" }
        val random = ByteArray(8).also(secureRandom::nextBytes)
        return byteArrayOf(0) + BigInteger.valueOf(timestampMillis).toFixedBigEndian(8) + random
    }

    private fun resolveTopic(context: Hsa2BootContext): String = context.bridgeInitiateData
        .strictStringOrNull("apnsTopic")
        ?: throw BridgePromptException("Apple did not return a bridge topic")

    private fun resolveWebSocketHost(context: Hsa2BootContext): String {
        val bridge = context.bridgeInitiateData
        bridge.strictStringOrNull("webSocketUrl")?.let { value ->
            val normalized = when {
                value.startsWith("wss://") -> "https://${value.removePrefix("wss://")}"
                value.startsWith("https://") -> value
                "://" in value -> throw BridgePromptException("Apple returned an invalid bridge URL")
                else -> "https://$value"
            }
            return AppleEndpointPolicy.requireAllowed(normalized).host
        }
        return when (bridge.strictStringOrNull("apnsEnvironment")) {
            "prod" -> "websocket.push.apple.com"
            "sandbox" -> "websocket.sandbox.push.apple.com"
            else -> throw BridgePromptException("Apple did not return a bridge host")
        }
    }

    private fun JsonObject.strictStringOrNull(name: String): String? {
        val value = this[name] ?: return null
        val primitive = value as? JsonPrimitive ?: return null
        return primitive.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)
    }

    internal data class BridgeKeyMaterial(
        val publicKey: ByteArray,
        val sign: (ByteArray) -> ByteArray,
    )

    internal class BridgePromptException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    internal class BridgeVerificationException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    private class InvalidBridgeNonceException(val serverTimestampSeconds: Long) : Exception()

    private companion object {
        const val AUTH_ENDPOINT = "https://idmsa.apple.com/appleauth/auth"
        const val AUTH_ENDPOINT_ORIGIN = "https://idmsa.apple.com"
        const val BRIDGE_TIMEOUT_MILLIS = 30_000L
        const val STATUS_OK = 0
        const val STATUS_INVALID_NONCE = 2
        const val HTTP_PRECONDITION_FAILED = 412
        val VALID_STEP_STATUSES = setOf(200, 204, 409)
        val VALID_CODE_STATUSES = setOf(200, 204, 409, HTTP_PRECONDITION_FAILED)
        val DONE_DATA_BASE64 = Base64.getEncoder().encodeToString("done".toByteArray())

        fun generateKeyMaterial(): BridgeKeyMaterial {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            val keyPair = generator.generateKeyPair()
            val publicKey = keyPair.public as ECPublicKey
            val encodedPublicKey = byteArrayOf(0x04) +
                publicKey.w.affineX.toFixedBigEndian(32) +
                publicKey.w.affineY.toFixedBigEndian(32)
            return BridgeKeyMaterial(encodedPublicKey) { nonce ->
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(keyPair.private)
                    update(nonce)
                    sign()
                }
            }
        }
    }
}

private fun BigInteger.toFixedBigEndian(size: Int): ByteArray {
    val encoded = toByteArray().let { bytes ->
        if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }
    require(encoded.size <= size)
    return ByteArray(size).also { encoded.copyInto(it, destinationOffset = size - encoded.size) }
}
