package me.lvca.alba.protocol

import java.security.MessageDigest

internal fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
    parts.forEach(::update)
    digest()
}

/** Lower-case hex SHA-256 of the UTF-8 [value]. */
internal fun sha256Hex(value: String): String = sha256(value.toByteArray(Charsets.UTF_8)).toHex()

internal fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}
