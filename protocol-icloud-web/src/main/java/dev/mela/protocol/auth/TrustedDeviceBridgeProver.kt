package dev.mela.protocol.auth

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal interface BridgeProver {
    fun initialize(saltBase64: String, code: String)

    fun message1(): String

    fun processServerProof(serverMessage1Hex: String, serverConfirmationHex: String): String

    fun decryptMessage(ciphertextBase64: String): String
}

internal class BridgeProofRejectedException : IllegalArgumentException("Invalid bridge server confirmation")

internal class TrustedDeviceBridgeProver(
    private val scalarProvider: () -> BigInteger = ::randomNonZeroScalar,
) : BridgeProver {
    private var x: BigInteger? = null
    private var w0: BigInteger? = null
    private var w1: BigInteger? = null
    private var clientShare: ByteArray? = null
    private var verifierKey: ByteArray? = null

    override fun initialize(saltBase64: String, code: String) {
        val salt = decodeBase64(saltBase64)
        val derived = BridgeScrypt.derive(
            password = code.toByteArray(Charsets.UTF_8),
            salt = salt,
            n = SCRYPT_N,
            r = SCRYPT_R,
            p = SCRYPT_P,
            outputBytes = SCRYPT_OUTPUT_BYTES,
        )
        x = scalarProvider().also { require(it.signum() > 0 && it < P256_ORDER) }
        w0 = BigInteger(1, derived.copyOfRange(0, 32))
        w1 = BigInteger(1, derived.copyOfRange(32, 64))
        clientShare = null
        verifierKey = null
    }

    override fun message1(): String {
        val clientScalar = requireNotNull(x) { "Bridge prover has not been initialized" }
        val passwordScalar = requireNotNull(w0)
        check(isOnCurve(GENERATOR))
        check(isOnCurve(decodePoint(SPAKE2_M)))
        val point = add(
            multiply(GENERATOR, clientScalar),
            multiply(decodePoint(SPAKE2_M), passwordScalar),
        )
        check(isOnCurve(point))
        return encodePoint(point).also { clientShare = it }
            .toHex()
    }

    override fun processServerProof(
        serverMessage1Hex: String,
        serverConfirmationHex: String,
    ): String {
        val clientScalar = requireNotNull(x) { "Bridge prover has not been initialized" }
        val passwordScalar0 = requireNotNull(w0)
        val passwordScalar1 = requireNotNull(w1)
        val clientPointBytes = requireNotNull(clientShare) { "Bridge message 1 has not been generated" }
        val serverPointBytes = serverMessage1Hex.hexToBytes()
        val serverPoint = decodePoint(serverPointBytes)
        val adjusted = add(
            serverPoint,
            negate(multiply(decodePoint(SPAKE2_N), passwordScalar0)),
        )
        require(!adjusted.isInfinity) { "Invalid bridge server point" }
        val yPoint = multiply(adjusted, clientScalar)
        val verifierPoint = multiply(adjusted, passwordScalar1)
        val transcript = concatenateLengthPrefixed(
            SPAKE2_CONTEXT,
            CLIENT_IDENTITY,
            SERVER_IDENTITY,
            encodePoint(decodePoint(SPAKE2_M)),
            encodePoint(decodePoint(SPAKE2_N)),
            clientPointBytes,
            encodePoint(serverPoint),
            encodePoint(yPoint),
            encodePoint(verifierPoint),
            passwordScalar0.toUnsignedBytes(),
        )
        val transcriptHash = sha256(transcript)
        val confirmationKeys = deriveKey(transcriptHash, CONFIRMATION_KEYS, 64)
        val clientConfirmationKey = confirmationKeys.copyOfRange(0, 32)
        val serverConfirmationKey = confirmationKeys.copyOfRange(32, 64)
        val expectedServerConfirmation = hmacSha256(serverConfirmationKey, clientPointBytes)
        val receivedServerConfirmation = serverConfirmationHex.hexToBytes()
        if (!MessageDigest.isEqual(expectedServerConfirmation, receivedServerConfirmation)) {
            throw BridgeProofRejectedException()
        }

        val rawKey = deriveKey(transcriptHash, SHARED_KEY, 32)
        verifierKey = deriveKey(rawKey, VERIFIER_KEY_INFO, 32)
        return hmacSha256(clientConfirmationKey, serverPointBytes).toHex()
    }

    override fun decryptMessage(ciphertextBase64: String): String {
        val key = requireNotNull(verifierKey) { "Bridge proof has not completed" }
        val payload = decodeBase64(ciphertextBase64)
        require(payload.size >= 1 + GCM_IV_BYTES + GCM_TAG_BYTES) { "Malformed bridge payload" }
        val version = payload[0].toInt() and 0xff
        require(version == 0) { "Unsupported bridge encryption version" }
        val iv = payload.copyOfRange(1, 1 + GCM_IV_BYTES)
        val tagStart = 1 + GCM_IV_BYTES
        val tag = payload.copyOfRange(tagStart, tagStart + GCM_TAG_BYTES)
        val ciphertext = payload.copyOfRange(tagStart + GCM_TAG_BYTES, payload.size)
        val encryptedWithTrailingTag = ciphertext + tag
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BYTES * 8, iv),
            )
            cipher.updateAAD(byteArrayOf(version.toByte()))
            cipher.doFinal(encryptedWithTrailingTag).toString(Charsets.UTF_8)
        }.getOrElse { throw IllegalArgumentException("Malformed bridge payload", it) }
    }

    private data class Point(val x: BigInteger?, val y: BigInteger?) {
        val isInfinity: Boolean get() = x == null || y == null
    }

    private companion object {
        val P256_P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
        val P256_A = P256_P - BigInteger.valueOf(3)
        val P256_B = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
        val P256_ORDER = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
        val GENERATOR = Point(
            BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16),
            BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16),
        )
        val INFINITY = Point(null, null)
        val SPAKE2_M = "02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f".hexToBytes()
        val SPAKE2_N = "03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49".hexToBytes()
        val SPAKE2_CONTEXT = "SPAKE2Web".toByteArray()
        val CLIENT_IDENTITY = "com.apple.security.webprover".toByteArray()
        val SERVER_IDENTITY = "com.apple.security.webverifier".toByteArray()
        val CONFIRMATION_KEYS = "ConfirmationKeys".toByteArray()
        val SHARED_KEY = "SharedKey".toByteArray()
        val VERIFIER_KEY_INFO = "webVerifier".toByteArray()
        val TWO = BigInteger.valueOf(2)

        const val SCRYPT_N = 16_384
        const val SCRYPT_R = 8
        const val SCRYPT_P = 1
        const val SCRYPT_OUTPUT_BYTES = 64
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BYTES = 16

        fun randomNonZeroScalar(): BigInteger {
            val random = SecureRandom()
            while (true) {
                val scalar = BigInteger(P256_ORDER.bitLength(), random)
                if (scalar.signum() > 0 && scalar < P256_ORDER) return scalar
            }
        }

        fun decodePoint(encoded: ByteArray): Point {
            val point = when {
                encoded.size == 65 && encoded[0] == 0x04.toByte() -> Point(
                    BigInteger(1, encoded.copyOfRange(1, 33)),
                    BigInteger(1, encoded.copyOfRange(33, 65)),
                )

                encoded.size == 33 && encoded[0] in byteArrayOf(0x02, 0x03) -> {
                    val x = BigInteger(1, encoded.copyOfRange(1, 33))
                    val right = (x.modPow(BigInteger.valueOf(3), P256_P) + P256_A * x + P256_B)
                        .mod(P256_P)
                    var y = right.modPow((P256_P + BigInteger.ONE) / BigInteger.valueOf(4), P256_P)
                    val odd = y.testBit(0)
                    if (odd != (encoded[0] == 0x03.toByte())) y = P256_P - y
                    Point(x, y)
                }

                else -> throw IllegalArgumentException("Unsupported P-256 point encoding")
            }
            require(isOnCurve(point)) { "Invalid P-256 point" }
            return point
        }

        fun encodePoint(point: Point): ByteArray {
            require(!point.isInfinity) { "Cannot encode the point at infinity" }
            return byteArrayOf(0x04) +
                requireNotNull(point.x).toFixedUnsignedBytes(32) +
                requireNotNull(point.y).toFixedUnsignedBytes(32)
        }

        fun isOnCurve(point: Point): Boolean {
            if (point.isInfinity) return false
            val x = requireNotNull(point.x)
            val y = requireNotNull(point.y)
            return (y.modPow(TWO, P256_P) -
                (x.modPow(BigInteger.valueOf(3), P256_P) + P256_A * x + P256_B))
                .mod(P256_P) == BigInteger.ZERO
        }

        fun negate(point: Point): Point = if (point.isInfinity) {
            point
        } else {
            Point(point.x, P256_P - requireNotNull(point.y))
        }

        fun add(left: Point, right: Point): Point {
            if (left.isInfinity) return right
            if (right.isInfinity) return left
            val leftX = requireNotNull(left.x)
            val leftY = requireNotNull(left.y)
            val rightX = requireNotNull(right.x)
            val rightY = requireNotNull(right.y)
            if (leftX == rightX && (leftY + rightY).mod(P256_P) == BigInteger.ZERO) return INFINITY
            val slope = if (leftX == rightX && leftY == rightY) {
                if (leftY == BigInteger.ZERO) return INFINITY
                (BigInteger.valueOf(3) * leftX * leftX + P256_A) *
                    (TWO * leftY).modInverse(P256_P)
            } else {
                (rightY - leftY) * (rightX - leftX).mod(P256_P).modInverse(P256_P)
            }.mod(P256_P)
            val resultX = (slope * slope - leftX - rightX).mod(P256_P)
            val resultY = (slope * (leftX - resultX) - leftY).mod(P256_P)
            return Point(resultX, resultY)
        }

        fun multiply(point: Point, rawScalar: BigInteger): Point {
            var scalar = rawScalar.mod(P256_ORDER)
            var result = INFINITY
            var addend = point
            while (scalar.signum() > 0) {
                if (scalar.testBit(0)) result = add(result, addend)
                addend = add(addend, addend)
                scalar = scalar.shiftRight(1)
            }
            return result
        }

        fun concatenateLengthPrefixed(vararg parts: ByteArray): ByteArray {
            val output = ByteArrayOutputStream()
            parts.forEach { part ->
                output.write(
                    ByteBuffer.allocate(Long.SIZE_BYTES)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putLong(part.size.toLong())
                        .array(),
                )
                output.write(part)
            }
            return output.toByteArray()
        }

        fun deriveKey(input: ByteArray, info: ByteArray, bytes: Int): ByteArray {
            val zeroSalt = ByteArray(32)
            val pseudoRandomKey = hmacSha256(zeroSalt, input)
            val output = ByteArrayOutputStream()
            var previous = ByteArray(0)
            var counter = 1
            while (output.size() < bytes) {
                previous = hmacSha256(
                    pseudoRandomKey,
                    previous + info + byteArrayOf(counter.toByte()),
                )
                output.write(previous)
                counter += 1
            }
            return output.toByteArray().copyOf(bytes)
        }

        fun hmacSha256(key: ByteArray, value: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(value)
        }

        fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

        fun decodeBase64(value: String): ByteArray = runCatching {
            Base64.getDecoder().decode(value)
        }.getOrElse { throw IllegalArgumentException("Malformed base64 bridge payload", it) }
    }
}

internal fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Malformed hexadecimal bridge payload" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toIntOrNull(16)?.toByte()
            ?: throw IllegalArgumentException("Malformed hexadecimal bridge payload")
    }
}

private fun BigInteger.toUnsignedBytes(): ByteArray {
    val encoded = toByteArray()
    return if (encoded.size > 1 && encoded[0] == 0.toByte()) encoded.copyOfRange(1, encoded.size) else encoded
}

private fun BigInteger.toFixedUnsignedBytes(size: Int): ByteArray {
    val source = toUnsignedBytes()
    require(source.size <= size) { "Integer does not fit bridge point coordinate" }
    return ByteArray(size).also { source.copyInto(it, destinationOffset = size - source.size) }
}
