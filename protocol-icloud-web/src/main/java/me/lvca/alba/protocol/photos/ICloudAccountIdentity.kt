package me.lvca.alba.protocol.photos

import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.sha256
import me.lvca.alba.protocol.sha256Hex
import me.lvca.alba.protocol.toHex

/** Identifiers derived from an Apple account. They are persisted, so their derivation must not change. */
internal object ICloudAccountIdentity {
    /** The account the engine binds uploads and edits to. */
    fun accountId(session: AppleSessionSnapshot): String = stableId("icloud-account", session.dsid)

    /** The short prefix of this account's catalog media IDs. */
    fun namespace(session: AppleSessionSnapshot): String =
        sha256(session.dsid.toByteArray(Charsets.UTF_8)).copyOf(8).toHex()

    /** A stable hash of [values] separated by NUL. */
    fun stableId(vararg values: String): String = sha256Hex(values.joinToString("\u0000"))
}
