package me.lvca.alba.protocol.photos

import kotlinx.serialization.json.*
import me.lvca.alba.engine.model.GalleryCollection

internal data class SharedAlbumRecord(
    val collection: GalleryCollection,
    /** [LEGACY_SCOPE] for shared streams, otherwise the CloudKit database: "private" or "shared". */
    val scope: String,
    val owner: String,
    val zone: String,
    val location: String? = null,
    val ctag: String? = null,
) {
    val isLegacy: Boolean get() = scope == LEGACY_SCOPE

    fun zoneId() = buildJsonObject {
        put("zoneName", zone)
        put("ownerRecordName", owner)
        put("zoneType", "REGULAR_CUSTOM_ZONE")
    }

    companion object {
        const val LEGACY_SCOPE = "legacy"
    }
}
