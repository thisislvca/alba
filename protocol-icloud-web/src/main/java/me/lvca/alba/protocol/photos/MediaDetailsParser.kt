package me.lvca.alba.protocol.photos

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import me.lvca.alba.engine.model.MediaDetails
import me.lvca.alba.protocol.field
import me.lvca.alba.protocol.fieldText
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * Reads a CPLAsset's descriptive fields. Field names and encodings follow the open-source pyicloud client
 * (timlaing/pyicloud `photos_cloudkit/materialize.py`): `captionEnc` and `extendedDescEnc` are base64 UTF-8 text,
 * `locationEnc` is a base64 property list with `lat`, `lon` and `alt`, and `timeZoneOffset` is in seconds.
 */
internal object MediaDetailsParser {
    private const val MAX_TEXT = 2_000
    private const val MAX_BURST_ID = 200

    fun parse(asset: JsonObject): MediaDetails {
        val location = asset.fieldText("locationEnc")?.let(::decodeBase64)?.let(BinaryPlist::parse) as? Map<*, *>
        val latitude = (location?.get("lat") as? Number)?.toDouble()
        val longitude = (location?.get("lon") as? Number)?.toDouble()
        val valid = MediaDetails.validLocation(latitude, longitude)
        return MediaDetails(
            caption = asset.text("captionEnc"),
            description = asset.text("extendedDescEnc"),
            latitude = latitude.takeIf { valid },
            longitude = longitude.takeIf { valid },
            altitude = (location?.get("alt") as? Number)?.toDouble()?.takeIf { valid && it.isFinite() },
            timeZoneOffsetSeconds = (asset.field("timeZoneOffset") as? JsonPrimitive)?.doubleOrNull?.toInt()
                ?.takeIf { it in -MAX_OFFSET_SECONDS..MAX_OFFSET_SECONDS },
            burstId = asset.fieldText("burstId")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_BURST_ID },
        )
    }

    private const val MAX_OFFSET_SECONDS = 18 * 60 * 60

    /** Base64 UTF-8 text, or the value as stored when it is not base64, as pyicloud's `decode_encrypted_text`. */
    private fun JsonObject.text(name: String): String? {
        val value = (field(name) as? JsonPrimitive)?.contentOrNull ?: return null
        val bytes = decodeBase64(value)
        val text = bytes?.let {
            runCatching {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(it)).toString()
            }.getOrNull()
        } ?: value
        return text.trim().takeIf { it.isNotEmpty() }?.take(MAX_TEXT)
    }

    private fun decodeBase64(value: String): ByteArray? = runCatching { Base64.getDecoder().decode(value) }.getOrNull()
}
