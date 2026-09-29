package dev.mela.protocol.auth

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AppleSrpClientTest {
    @Test
    fun `s2k proof matches pinned pyicloud oracle`() {
        assertOracleProof(
            protocol = AppleSrpProtocol.S2K,
            expectedM1 = "kXj2brMlgwn4vCbwjaYyMUuNUWxNtZ7fLrdbZXa7Jr0=",
            expectedM2 = "fNX29t9/8TiXspWqJ0p1hIZoWQXgfb2jxJpCS1vRTZo=",
        )
    }

    @Test
    fun `s2k fo proof matches pinned pyicloud oracle`() {
        assertOracleProof(
            protocol = AppleSrpProtocol.S2K_FO,
            expectedM1 = "Bow86Exu+SGp58UOcWYG0k6+FlfD1kOiuFwh++uk5hM=",
            expectedM2 = "rT0JrwsDXFI70G12EaS7calT5uphRg7OUmZHW+pGgW0=",
        )
    }

    private fun assertOracleProof(
        protocol: AppleSrpProtocol,
        expectedM1: String,
        expectedM2: String,
    ) {
        val client = AppleSrpClient(
            username = "luca@example.com",
            password = "correct horse battery staple",
            ephemeralSecret = ByteArray(256) { index -> index.toByte() },
        )
        val proof = client.processChallenge(
            salt = hex("00112233445566778899aabbccddeeff"),
            serverPublicB = Base64.getDecoder().decode(SERVER_PUBLIC_B),
            iterations = 2_048,
            protocol = protocol,
        )

        assertEquals(EXPECTED_PUBLIC_A, Base64.getEncoder().encodeToString(proof.publicA))
        assertArrayEquals(Base64.getDecoder().decode(expectedM1), proof.clientProof)
        assertArrayEquals(Base64.getDecoder().decode(expectedM2), proof.expectedServerProof)
    }

    private fun hex(value: String): ByteArray = value.chunked(2)
        .map { byte -> byte.toInt(16).toByte() }
        .toByteArray()

    private companion object {
        const val SERVER_PUBLIC_B = "rGvbQTJKmpvxZt5eE4lYL69ytmUZh+4H/DGSlD21YFCjcynLtKCZ7YGT4HV3Z6E91SMSq0sDMQ3Nf0ip2gT9UOgIOWntt2ewz2CVF5oWOrNmGgX71fqq6CkYqZYvC5O4Vfl5k+yXXuqoDXQK2/T/dHNZ0EHVwz6nHSgeRGsUdzvKl7Q6I/uAFna9IHpDbGSB8dK5B4cXRhpbnTLmiPh3SFRFI7UksNV9Xqd6J3XS7PoDLPvb9S+zeGFgJ5AE5Xrmr4dOcwPOUymczAQce8MI2CpWmPOo0MOCca41+Onb+7aUtcgD2J965DXeI21SX1R1m2XjcvzWjvIPpxEflu8yXg=="
        const val EXPECTED_PUBLIC_A = "WWKC5lGbUicZ8GGZTq9NM565bksojXKuRFBKI3lyFJY4TbUaPHRwOlIxJabg9EN9fdONWjIXcMVpgiwU7jmX+8OPCefkcefv+H+BuCLZeiwvvA+F7h/7xFzfsCbSxKqY1oh7aZ6ZbjnTo2E4/FOioW/8Dv2NW9QBbYiWnHQBx2cemzZYkhHtTA2Aoy2E5gIK7ytvZZgzdI9AvqZnV6xehj4rb8CuGL/M5jpjSjr0lY0KzkI7bk78d1JwUK+6kktafen8s5pThjvjhG2s7mE4G/VNDtGqCEMGZL6dygWmseXdueUEdXP106xep13Zn3dF2ACVRyJZDRU2dYGsL3xMdQ=="
    }
}
