package dev.mela.protocol.photos

import dev.mela.engine.model.GalleryCollection
import kotlinx.serialization.json.*

internal data class SharedAlbumRecord(
    val collection: GalleryCollection,
    val scope: String,
    val owner: String,
    val zone: String,
    val location: String? = null,
    val ctag: String? = null,
) {
    fun zoneId() = buildJsonObject {
        put("zoneName", zone)
        put("ownerRecordName", owner)
        put("zoneType", "REGULAR_CUSTOM_ZONE")
    }
}
