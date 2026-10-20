package me.lvca.alba.protocol.photos

import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.protocol.sha256Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Durable phases of shared-album writes. An operation whose response was lost is reconciled from
 * its saved phase and data instead of being sent again.
 */
internal class SharedAlbumJournal(
    private val journal: UploadJournal?,
    private val account: () -> String,
) {
    fun binding() = AccountBinding("shared:${account()}", 0, 0)

    fun store(): UploadJournal = requireNotNull(journal) { "Durable shared album storage is unavailable." }

    suspend fun load(key: String): UploadCheckpoint? = store().load(binding(), key)

    suspend fun save(key: String, phase: String, data: JsonObject) =
        store().save(binding(), key, UploadCheckpoint(phase, data.toString()))

    companion object {
        /** A journal key that names an operation by its content, never by user-visible text. */
        fun key(kind: String, identity: String) = "$kind:" + sha256Hex(identity)

        fun UploadCheckpoint.data(): JsonObject = Json.parseToJsonElement(payload).jsonObject
    }
}
