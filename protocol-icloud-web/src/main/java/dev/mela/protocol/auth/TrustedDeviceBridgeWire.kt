package dev.mela.protocol.auth

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okio.ByteString.Companion.encodeUtf8

internal data class BridgeConnectionResponse(
    val pushTokenBase64: String,
    val status: Int,
    val serverTimestampSeconds: Long?,
)

internal data class BridgePushMessage(
    val topic: ByteArray,
    val messageId: Long,
    val payload: ByteArray,
)

internal data class BridgeServerMessage(
    val connection: BridgeConnectionResponse? = null,
    val push: BridgePushMessage? = null,
    val subscriptionStatus: Int? = null,
)

internal data class BridgePushPayload(
    val sessionId: String,
    val hasExplicitSessionId: Boolean,
    val nextStep: String? = null,
    val ruiUrlKey: String? = null,
    val transactionId: String? = null,
    val salt: String? = null,
    val machineId: String? = null,
    val idmsData: String? = null,
    val akData: JsonElement? = null,
    val data: String? = null,
    val encryptedCode: String? = null,
    val errorCode: Int? = null,
)

internal object TrustedDeviceBridgeWire {
    fun encodeConnection(publicKey: ByteArray, nonce: ByteArray, signature: ByteArray): ByteArray {
        val wrappedSignature = if (
            signature.size >= SIGNATURE_PREFIX.size &&
            signature.copyOfRange(0, SIGNATURE_PREFIX.size).contentEquals(SIGNATURE_PREFIX)
        ) {
            signature
        } else {
            SIGNATURE_PREFIX + signature
        }
        val connection = concatenate(
            encodeBytes(1, publicKey),
            encodeBytes(2, nonce),
            encodeBytes(3, wrappedSignature),
            encodeBytes(5, encodeUnsigned(1, CONNECTION_EXPIRATION_SECONDS)),
        )
        return encodeBytes(SERVER_CONNECTION, connection)
    }

    fun encodeTopicFilter(topic: String): ByteArray = encodeBytes(3, encodeString(1, topic))

    fun encodeAcknowledgement(topic: ByteArray, messageId: Long): ByteArray = encodeBytes(
        2,
        concatenate(
            encodeBytes(1, topic),
            encodeUnsigned(2, messageId),
        ),
    )

    fun topicHash(topic: String): String = topic.encodeUtf8().sha1().hex()

    fun topicName(topic: ByteArray, expectedTopic: String): String = when {
        topic.toHex() == topicHash(expectedTopic) -> expectedTopic
        else -> topic.toString(Charsets.UTF_8)
    }

    fun decodeServerMessage(message: ByteArray): BridgeServerMessage {
        require(message.size <= MAX_FRAME_BYTES) { "Bridge frame is too large" }
        val fields = decodeFields(message)
        val connection = fields.firstBytes(SERVER_CONNECTION)?.let(::decodeConnection)
        val push = fields.firstBytes(SERVER_PUSH)?.let(::decodePush)
        val subscriptionStatus = fields.firstBytes(SERVER_SUBSCRIPTION)
            ?.let(::decodeSubscriptionStatus)
        return BridgeServerMessage(connection, push, subscriptionStatus)
    }

    fun decodePushPayload(payload: ByteArray): BridgePushPayload {
        val root = parseJsonObject(payload)
            ?: throw IllegalArgumentException("Could not decode the trusted-device bridge push")
        val explicitSession = root.strictStringOrNull("sessionUUID")
        val flowId = root.strictStringOrNull("flowid")
        val sessionId = explicitSession ?: flowId
            ?: throw IllegalArgumentException("Bridge push is missing its session identifier")
        val nextStepElement = root["nextStep"]
        val nextStep = when (nextStepElement) {
            null -> null
            is JsonPrimitive -> if (nextStepElement.isString) {
                nextStepElement.contentOrNull?.requireNotBlank("nextStep")
            } else {
                nextStepElement.intOrNull?.toString()
                    ?: throw IllegalArgumentException("Malformed bridge nextStep")
            }

            else -> throw IllegalArgumentException("Malformed bridge nextStep")
        }
        val errorCode = root["ec"]?.let { value ->
            val primitive = value as? JsonPrimitive
                ?: throw IllegalArgumentException("Malformed bridge error code")
            if (primitive.isString) throw IllegalArgumentException("Malformed bridge error code")
            primitive.intOrNull ?: throw IllegalArgumentException("Malformed bridge error code")
        }

        return BridgePushPayload(
            sessionId = sessionId,
            hasExplicitSessionId = explicitSession != null,
            nextStep = nextStep,
            ruiUrlKey = root.strictStringOrNull("ruiURLKey"),
            transactionId = root.strictStringOrNull("txnid"),
            salt = root.strictStringOrNull("salt"),
            machineId = root.strictStringOrNull("mid"),
            idmsData = root.strictStringOrNull("idmsdata"),
            akData = root["akdata"],
            data = root.strictStringOrNull("data"),
            encryptedCode = root.strictStringOrNull("encryptedCode"),
            errorCode = errorCode,
        )
    }

    private fun parseJsonObject(payload: ByteArray): JsonObject? {
        val text = payload.toString(Charsets.UTF_8)
        parseObject(text)?.let { return it }
        var start = text.indexOf('{')
        while (start >= 0) {
            var depth = 0
            var inString = false
            var escaped = false
            for (index in start until text.length) {
                val character = text[index]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        character == '\\' -> escaped = true
                        character == '"' -> inString = false
                    }
                } else {
                    when (character) {
                        '"' -> inString = true
                        '{' -> depth += 1
                        '}' -> {
                            depth -= 1
                            if (depth == 0) {
                                parseObject(text.substring(start, index + 1))?.let { return it }
                                break
                            }
                        }
                    }
                }
            }
            start = text.indexOf('{', start + 1)
        }
        return null
    }

    private fun parseObject(value: String): JsonObject? = runCatching {
        JSON.parseToJsonElement(value).jsonObject
    }.getOrNull()

    private fun decodeConnection(bytes: ByteArray): BridgeConnectionResponse {
        val fields = decodeFields(bytes)
        val token = fields.firstBytes(1)?.toString(Charsets.US_ASCII).orEmpty()
        return BridgeConnectionResponse(
            pushTokenBase64 = token,
            status = fields.firstUnsigned(2)?.toInt() ?: 0,
            serverTimestampSeconds = fields.firstUnsigned(3),
        )
    }

    private fun decodePush(bytes: ByteArray): BridgePushMessage {
        val fields = decodeFields(bytes)
        return BridgePushMessage(
            topic = fields.firstBytes(1) ?: byteArrayOf(),
            messageId = fields.firstUnsigned(2) ?: 0,
            payload = fields.firstBytes(4) ?: byteArrayOf(),
        )
    }

    private fun decodeSubscriptionStatus(bytes: ByteArray): Int =
        decodeFields(bytes).firstUnsigned(3)?.toInt() ?: 0

    private fun encodeString(field: Int, value: String): ByteArray =
        encodeBytes(field, value.toByteArray(Charsets.UTF_8))

    private fun encodeBytes(field: Int, value: ByteArray): ByteArray =
        encodeVarint(((field shl 3) or WIRE_LENGTH_DELIMITED).toLong()) +
            encodeVarint(value.size.toLong()) +
            value

    private fun encodeUnsigned(field: Int, value: Long): ByteArray =
        encodeVarint(((field shl 3) or WIRE_VARINT).toLong()) + encodeVarint(value)

    private fun encodeVarint(rawValue: Long): ByteArray {
        require(rawValue >= 0) { "Negative bridge varint" }
        var value = rawValue
        val output = ByteArrayOutputStream()
        while (true) {
            val next = (value and 0x7f).toInt()
            value = value ushr 7
            output.write(if (value == 0L) next else next or 0x80)
            if (value == 0L) return output.toByteArray()
        }
    }

    private fun decodeFields(bytes: ByteArray): Map<Int, List<WireValue>> {
        var offset = 0
        val fields = mutableMapOf<Int, MutableList<WireValue>>()
        while (offset < bytes.size) {
            val key = readVarint(bytes, offset)
            offset = key.nextOffset
            val field = (key.value ushr 3).toInt()
            val wireType = (key.value and 0x07).toInt()
            require(field > 0) { "Malformed bridge protobuf field" }
            val value = when (wireType) {
                WIRE_VARINT -> readVarint(bytes, offset).let { decoded ->
                    offset = decoded.nextOffset
                    WireValue.Unsigned(decoded.value)
                }

                WIRE_LENGTH_DELIMITED -> {
                    val length = readVarint(bytes, offset)
                    offset = length.nextOffset
                    require(length.value in 0..Int.MAX_VALUE) { "Bridge protobuf field is too large" }
                    val end = offset.toLong() + length.value
                    require(end <= bytes.size) { "Truncated bridge protobuf field" }
                    WireValue.Bytes(bytes.copyOfRange(offset, end.toInt())).also { offset = end.toInt() }
                }

                else -> throw IllegalArgumentException("Unsupported bridge protobuf wire type")
            }
            fields.getOrPut(field, ::mutableListOf) += value
        }
        return fields
    }

    private fun readVarint(bytes: ByteArray, startOffset: Int): DecodedVarint {
        var offset = startOffset
        var value = 0L
        var shift = 0
        while (offset < bytes.size && offset - startOffset < 10) {
            val next = bytes[offset].toInt() and 0xff
            offset += 1
            if (shift == 63 && next and 0xfe != 0) {
                throw IllegalArgumentException("Malformed bridge protobuf varint")
            }
            value = value or ((next and 0x7f).toLong() shl shift)
            if (next and 0x80 == 0) return DecodedVarint(value, offset)
            shift += 7
        }
        throw IllegalArgumentException("Truncated bridge protobuf varint")
    }

    private fun Map<Int, List<WireValue>>.firstBytes(field: Int): ByteArray? =
        (this[field]?.firstOrNull() as? WireValue.Bytes)?.value

    private fun Map<Int, List<WireValue>>.firstUnsigned(field: Int): Long? =
        (this[field]?.firstOrNull() as? WireValue.Unsigned)?.value

    private fun JsonObject.strictStringOrNull(name: String): String? {
        val value = this[name] ?: return null
        val primitive = value as? JsonPrimitive
            ?: throw IllegalArgumentException("Malformed bridge $name")
        if (!primitive.isString) throw IllegalArgumentException("Malformed bridge $name")
        return primitive.contentOrNull?.requireNotBlank(name)
    }

    private fun String.requireNotBlank(name: String): String = also {
        require(it.isNotBlank()) { "Malformed bridge $name" }
    }

    private fun concatenate(vararg values: ByteArray): ByteArray = values.fold(byteArrayOf()) { all, next ->
        all + next
    }

    private sealed interface WireValue {
        data class Unsigned(val value: Long) : WireValue
        data class Bytes(val value: ByteArray) : WireValue
    }

    private data class DecodedVarint(val value: Long, val nextOffset: Int)

    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val SERVER_CONNECTION = 1
    private const val SERVER_PUSH = 2
    private const val SERVER_SUBSCRIPTION = 3
    private const val CONNECTION_EXPIRATION_SECONDS = 86_400L
    private const val MAX_FRAME_BYTES = 1_048_576
    private val SIGNATURE_PREFIX = byteArrayOf(0x01, 0x03)
    private val JSON = Json { ignoreUnknownKeys = true }
}
