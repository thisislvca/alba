package dev.mela.protocol.auth

import dev.mela.protocol.account.AppleSessionSnapshot
import dev.mela.protocol.account.PersistedCookie
import dev.mela.protocol.network.AppleHttpRequest
import dev.mela.protocol.network.AppleHttpResponse
import dev.mela.protocol.network.AppleHttpTransport
import dev.mela.protocol.network.AppleSessionHeaders
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedDeviceBridgeTest {
    @Test
    fun `bridge runs step zero two four six and closes ephemeral socket`() = runBlocking {
        val socket = FakeSocket(
            mutableListOf(
                connectionResponse("push-token".toByteArray()),
                push(INITIAL_PUSH, 2_300),
                push(STEP_FOUR_PUSH, 2_301),
                push(FINAL_PUSH, 2_302),
            ),
        )
        val transport = BridgeTransport()
        val prover = FakeProver()
        val bridge = TrustedDeviceBridge(
            transport = transport,
            socketFactory = BridgeSocketFactory { url, origin ->
                assertTrue(url.startsWith("wss://websocket.push.apple.com/v2/"))
                assertEquals("https://idmsa.apple.com", origin)
                socket
            },
            proverFactory = { prover },
            keyMaterialProvider = {
                TrustedDeviceBridge.BridgeKeyMaterial(
                    publicKey = byteArrayOf(0x04, 0x01),
                    sign = { byteArrayOf(0x30, 0x01) },
                )
            },
            sessionIdProvider = { SESSION_ID },
            nowMillis = { 1_700_000_000_000 },
            secureRandom = SecureRandom(byteArrayOf(1, 2, 3)),
            timeoutMillis = 1_000,
        )

        val state = bridge.start(BOOT_CONTEXT, mapOf("scnt" to "test-scnt"))

        assertEquals("707573682d746f6b656e", state.pushToken)
        assertEquals("2", state.nextStep)
        assertFalse(state.usesLegacyVerifier)
        assertFalse(socket.closed)
        assertTrue(bridge.validateCode(state, mapOf("scnt" to "test-scnt"), "050044"))

        assertEquals("050044", prover.code)
        assertEquals("aa01", prover.serverMessage)
        assertEquals("bb02", prover.serverConfirmation)
        assertEquals("ciphertext", prover.ciphertext)
        assertTrue(socket.closed)
        assertNull(state.socket)
        assertEquals(
            listOf(
                "/appleauth/auth/bridge/step/0",
                "/appleauth/auth/bridge/step/2",
                "/appleauth/auth/bridge/step/4",
                "/appleauth/auth/bridge/code/validate",
                "/appleauth/auth/bridge/step/6",
            ),
            transport.requests.map { java.net.URI(it.url).path },
        )
        val bodies = transport.requests.map { Json.parseToJsonElement(requireNotNull(it.body)).jsonObject }
        assertEquals(SESSION_ID, bodies[0]["sessionUUID"].toString().trim('"'))
        assertEquals("707573682d746f6b656e", bodies[0]["ptkn"].toString().trim('"'))
        assertEquals("q80=", bodies[1]["data"].toString().trim('"'))
        assertEquals("initial-idms", bodies[1]["idmsdata"].toString().trim('"'))
        assertEquals("7wE=", bodies[2]["data"].toString().trim('"'))
        assertEquals("derived-device-code", bodies[3]["code"].toString().trim('"'))
        assertEquals("ZG9uZQ==", bodies[4]["data"].toString().trim('"'))
        assertTrue(socket.sent.size >= 4)
    }

    @Test
    fun `wire parser accepts flow id and embedded JSON but rejects typed garbage`() {
        val payload = TrustedDeviceBridgeWire.decodePushPayload(
            byteArrayOf(0x12, 0x01) +
                "{\"flowid\":\"bridge-flow\",\"nextStep\":2,\"extra\":true}".toByteArray() +
                byteArrayOf(0x18),
        )

        assertEquals("bridge-flow", payload.sessionId)
        assertFalse(payload.hasExplicitSessionId)
        assertEquals("2", payload.nextStep)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            TrustedDeviceBridgeWire.decodePushPayload(
                "{\"sessionUUID\":123,\"nextStep\":\"2\"}".toByteArray(),
            )
        }
    }

    private class FakeSocket(private val messages: MutableList<ByteArray>) : BridgeSocket {
        val sent = mutableListOf<ByteArray>()
        var closed = false

        override suspend fun readMessage(): ByteArray = messages.removeFirst()

        override fun send(payload: ByteArray) {
            sent += payload
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeProver : BridgeProver {
        var code: String? = null
        var serverMessage: String? = null
        var serverConfirmation: String? = null
        var ciphertext: String? = null

        override fun initialize(saltBase64: String, code: String) {
            assertEquals("MDEyMzQ1Njc4OWFiY2RlZg==", saltBase64)
            this.code = code
        }

        override fun message1(): String = "abcd"

        override fun processServerProof(
            serverMessage1Hex: String,
            serverConfirmationHex: String,
        ): String {
            serverMessage = serverMessage1Hex
            serverConfirmation = serverConfirmationHex
            return "ef01"
        }

        override fun decryptMessage(ciphertextBase64: String): String {
            ciphertext = ciphertextBase64
            return "derived-device-code"
        }
    }

    private class BridgeTransport : AppleHttpTransport {
        val requests = mutableListOf<AppleHttpRequest>()
        override val sessionHeaders = AppleSessionHeaders()

        override suspend fun execute(request: AppleHttpRequest): AppleHttpResponse {
            requests += request
            val code = if (request.url.endsWith("/bridge/code/validate")) 409 else 200
            return AppleHttpResponse(code, emptyMap(), "{}")
        }

        override suspend fun stream(url: String, output: OutputStream) = error("Not used")

        override fun snapshotCookies(): List<PersistedCookie> = emptyList()

        override fun restore(snapshot: AppleSessionSnapshot) = Unit

        override fun clear() = Unit
    }

    private companion object {
        const val TOPIC = "com.apple.idmsauthwidget"
        const val SESSION_ID = "bridge-session"
        val BOOT_CONTEXT = Hsa2BootContext(
            authInitialRoute = "auth/bridge/step",
            hasTrustedDevices = true,
            authFactors = listOf("web_piggybacking", "sms"),
            bridgeInitiateData = Json.parseToJsonElement(
                """{"apnsTopic":"$TOPIC","apnsEnvironment":"prod","webSocketUrl":"websocket.push.apple.com"}""",
            ).jsonObject,
            sourceAppId = "1159",
        )
        val INITIAL_PUSH = """
            {
              "sessionUUID":"$SESSION_ID",
              "nextStep":"2",
              "txnid":"2300_282820214_S",
              "salt":"MDEyMzQ1Njc4OWFiY2RlZg==",
              "idmsdata":"initial-idms",
              "akdata":{"lat":49.52}
            }
        """.trimIndent()
        val STEP_FOUR_PUSH = run {
            val data = Base64.getEncoder().encodeToString(
                (Base64.getEncoder().encodeToString("aa01".hexToBytes()) + "_" +
                    Base64.getEncoder().encodeToString("bb02".hexToBytes())).toByteArray(),
            )
            """
                {
                  "sessionUUID":"$SESSION_ID",
                  "nextStep":"4",
                  "data":"$data",
                  "idmsdata":"step4-idms",
                  "akdata":{"step":4}
                }
            """.trimIndent()
        }
        val FINAL_PUSH = """
            {
              "sessionUUID":"$SESSION_ID",
              "nextStep":"6",
              "encryptedCode":"ciphertext",
              "idmsdata":"step6-idms",
              "akdata":{"step":6}
            }
        """.trimIndent()

        fun connectionResponse(pushToken: ByteArray): ByteArray = bytesField(
            1,
            bytesField(1, Base64.getEncoder().encode(pushToken)) + unsignedField(2, 0),
        )

        fun push(json: String, messageId: Long): ByteArray = bytesField(
            2,
            bytesField(1, TrustedDeviceBridgeWire.topicHash(TOPIC).hexToBytes()) +
                unsignedField(2, messageId) +
                bytesField(4, json.toByteArray()),
        )

        fun bytesField(field: Int, value: ByteArray): ByteArray =
            varint(((field shl 3) or 2).toLong()) + varint(value.size.toLong()) + value

        fun unsignedField(field: Int, value: Long): ByteArray =
            varint((field shl 3).toLong()) + varint(value)

        fun varint(raw: Long): ByteArray {
            var value = raw
            val output = ByteArrayOutputStream()
            do {
                val next = (value and 0x7f).toInt()
                value = value ushr 7
                output.write(if (value == 0L) next else next or 0x80)
            } while (value != 0L)
            return output.toByteArray()
        }
    }
}
