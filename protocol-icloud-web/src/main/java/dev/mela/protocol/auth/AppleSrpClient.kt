package dev.mela.protocol.auth

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class AppleSrpProtocol(val wireValue: String) {
    S2K("s2k"),
    S2K_FO("s2k_fo"),
    ;

    companion object {
        fun fromWireValue(value: String): AppleSrpProtocol = entries.firstOrNull {
            it.wireValue == value
        } ?: error("Unsupported Apple SRP protocol: $value")
    }
}

data class AppleSrpProof(
    val publicA: ByteArray,
    val clientProof: ByteArray,
    val expectedServerProof: ByteArray,
)

class AppleSrpClient(
    private val username: String,
    private val password: String,
    ephemeralSecret: ByteArray = secureEphemeralSecret(),
) {
    private val secretA = positive(ephemeralSecret)
    private val publicAValue = GENERATOR.modPow(secretA, MODULUS)

    val publicA: ByteArray = publicAValue.toMinimalUnsignedBytes()

    fun processChallenge(
        salt: ByteArray,
        serverPublicB: ByteArray,
        iterations: Int,
        protocol: AppleSrpProtocol,
    ): AppleSrpProof {
        require(iterations > 0) { "Apple SRP iterations must be positive" }
        val publicBValue = positive(serverPublicB)
        require(publicBValue.mod(MODULUS) != BigInteger.ZERO) {
            "Apple SRP server public value is invalid"
        }

        val width = MODULUS.toMinimalUnsignedBytes().size
        val multiplier = positive(sha256(MODULUS.padded(width), GENERATOR.padded(width)))
        val scrambling = positive(sha256(publicAValue.padded(width), publicBValue.padded(width)))
        require(scrambling != BigInteger.ZERO) { "Apple SRP scrambling parameter is invalid" }

        val passwordHash = sha256(password.toByteArray(Charsets.UTF_8))
        val passwordMaterial = when (protocol) {
            AppleSrpProtocol.S2K -> passwordHash
            AppleSrpProtocol.S2K_FO -> passwordHash.toHex().toByteArray(Charsets.US_ASCII)
        }
        val derivedPassword = pbkdf2HmacSha256(
            password = passwordMaterial,
            salt = salt,
            iterations = iterations,
            length = SHA_256_BYTES,
        )
        val innerPasswordHash = sha256(byteArrayOf(':'.code.toByte()), derivedPassword)
        val privateKey = positive(sha256(salt, innerPasswordHash))
        val verifier = GENERATOR.modPow(privateKey, MODULUS)
        val base = publicBValue.subtract(multiplier.multiply(verifier)).mod(MODULUS)
        val exponent = secretA.add(scrambling.multiply(privateKey))
        val sharedSecret = base.modPow(exponent, MODULUS)
        val sessionKey = sha256(sharedSecret.toMinimalUnsignedBytes())

        val modulusHash = sha256(MODULUS.toMinimalUnsignedBytes())
        val generatorHash = sha256(GENERATOR.padded(width))
        val xorHash = ByteArray(SHA_256_BYTES) { index ->
            (modulusHash[index].toInt() xor generatorHash[index].toInt()).toByte()
        }
        val clientProof = sha256(
            xorHash,
            sha256(username.toByteArray(Charsets.UTF_8)),
            salt,
            publicAValue.toMinimalUnsignedBytes(),
            publicBValue.toMinimalUnsignedBytes(),
            sessionKey,
        )
        val serverProof = sha256(
            publicAValue.toMinimalUnsignedBytes(),
            clientProof,
            sessionKey,
        )

        return AppleSrpProof(
            publicA = publicA,
            clientProof = clientProof,
            expectedServerProof = serverProof,
        )
    }

    private companion object {
        const val SHA_256_BYTES = 32
        const val EPHEMERAL_SECRET_BYTES = 256
        val GENERATOR = BigInteger.valueOf(2L)
        val MODULUS = BigInteger(
            """
            AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4
            A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50E8083969EDB767B0CF60
            95179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF
            747359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B907
            8717461A5B9D32E688F87748544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB37861
            60279004E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DB
            FBB694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73
            """.trimIndent().filterNot(Char::isWhitespace),
            16,
        )

        fun secureEphemeralSecret(): ByteArray = ByteArray(EPHEMERAL_SECRET_BYTES).also { bytes ->
            SecureRandom().nextBytes(bytes)
            bytes[0] = (bytes[0].toInt() or 0x80).toByte()
        }

        fun positive(bytes: ByteArray): BigInteger = BigInteger(1, bytes)

        fun BigInteger.toMinimalUnsignedBytes(): ByteArray {
            val signed = toByteArray()
            return if (signed.size > 1 && signed[0] == 0.toByte()) signed.copyOfRange(1, signed.size) else signed
        }

        fun BigInteger.padded(width: Int): ByteArray {
            val value = toMinimalUnsignedBytes()
            require(value.size <= width) { "SRP value exceeds modulus width" }
            return ByteArray(width - value.size) + value
        }

        fun sha256(vararg values: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
            values.forEach(::update)
            digest()
        }

        fun pbkdf2HmacSha256(
            password: ByteArray,
            salt: ByteArray,
            iterations: Int,
            length: Int,
        ): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(password, "HmacSHA256"))
            val hashLength = mac.macLength
            val blockCount = (length + hashLength - 1) / hashLength
            val derived = ByteArray(blockCount * hashLength)

            for (block in 1..blockCount) {
                val blockIndex = byteArrayOf(
                    (block ushr 24).toByte(),
                    (block ushr 16).toByte(),
                    (block ushr 8).toByte(),
                    block.toByte(),
                )
                var current = mac.doFinal(salt + blockIndex)
                val aggregate = current.copyOf()
                repeat(iterations - 1) {
                    current = mac.doFinal(current)
                    current.indices.forEach { index ->
                        aggregate[index] = (aggregate[index].toInt() xor current[index].toInt()).toByte()
                    }
                }
                aggregate.copyInto(derived, destinationOffset = (block - 1) * hashLength)
            }
            return derived.copyOf(length)
        }

        fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
