package dev.mela.protocol.photos

import dev.mela.protocol.auth.AppleProtocolError
import dev.mela.protocol.auth.AppleProtocolException
import dev.mela.protocol.network.AppleEndpointPolicy
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal data class CloudPhotoAsset(
    val assetRecordName: String,
    val fileName: String,
    val capturedAtEpochMillis: Long,
    val width: Int,
    val height: Int,
    val sourceRevision: String,
    val previewDownloadUrl: String,
    val originalDownloadUrl: String,
    val masterRecordName: String,
    val kind: dev.mela.engine.model.MediaKind,
    val mimeType: String,
    val durationMillis: Long?,
    val resourceFingerprint: String,
    val playbackUrl: String,
    val motionDownloadUrl: String?,
    val motionMimeType: String,
    val addedAtEpochMillis: Long? = null,
    val displayDownloadUrl: String = previewDownloadUrl,
    val isTrashed: Boolean = false,
    val byteCount: Long? = null,
)

internal data class ParsedCloudPhotoPage(
    val photos: List<CloudPhotoAsset>,
    val logicalAssetCount: Int,
    val syncToken: String?,
    val continuationMarker: String?,
)

internal class CloudKitPhotoResponseParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String, includeTrashed: Boolean = false, legacy: Boolean = false): ParsedCloudPhotoPage {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }
            .getOrNull()
            ?: malformed("Apple returned an invalid Photos response")
        val serverError = (root["errors"] as? JsonArray)
            ?.firstOrNull()
            ?.let { it as? JsonObject }
        if (serverError != null) {
            val reason = serverError.string("reason")
                ?: serverError.string("message")
                ?: "Apple rejected the Photos request"
            throw AppleProtocolException(AppleProtocolError.PHOTOS_UNAVAILABLE, reason)
        }

        val records = (root["records"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        val masters = records.filter { it.string("recordType") == MASTER_RECORD_TYPE }.associateBy { it.string("recordName") }
        val pairedRecords = records.mapNotNull { asset ->
            if (asset.string("recordType") != ASSET_RECORD_TYPE) return@mapNotNull null
            val masterName = (asset.fieldValue("masterRef") as? JsonObject)?.string("recordName") ?: return@mapNotNull null
            val master = masters[masterName] ?: return@mapNotNull null
            master to asset
        }
        val photos = pairedRecords.mapNotNull { (master, asset) -> parsePhoto(master, asset, includeTrashed, legacy) }

        return ParsedCloudPhotoPage(
            photos = photos,
            logicalAssetCount = pairedRecords.size,
            syncToken = root.string("syncToken"),
            continuationMarker = root.string("continuationMarker"),
        )
    }

    private fun parsePhoto(master: JsonObject, asset: JsonObject, includeTrashed: Boolean, legacy: Boolean): CloudPhotoAsset? {
        val assetRecordName = asset.string("recordName") ?: return null
        val trashed = asset.fieldString("isDeleted") in setOf("1", "true")
        if (asset.fieldString("isHidden") in setOf("1", "true") || asset.fieldString("isExpunged") in setOf("1", "true") || (trashed && !includeTrashed)) return null
        val movie = master.isMovie()
        val originalUrl = master.assetUrl("resOriginalRes") ?: return null
        val motionUrl = if (movie) null else master.assetUrl("resOriginalVidComplRes")
        motionUrl?.let(AppleEndpointPolicy::requireAllowed)
        val previewUrl = PREVIEW_RESOURCE_KEYS.firstNotNullOfOrNull { key -> master.assetUrl(key) }
            ?: ""
        // Opening a photo upgrades to full resolution. Scrolling never fetches an
        // original merely because Apple did not supply a smaller JPEG derivative.
        val displayUrl = if (movie) previewUrl else master.assetUrl("resJPEGFullRes") ?: originalUrl
        if (displayUrl.isNotEmpty()) AppleEndpointPolicy.requireAllowed(displayUrl)
        if (previewUrl.isNotEmpty()) AppleEndpointPolicy.requireAllowed(previewUrl)
        AppleEndpointPolicy.requireAllowed(originalUrl)

        val width = master.fieldInt("resOriginalWidth")
            ?: PREVIEW_WIDTH_KEYS.firstNotNullOfOrNull { key -> master.fieldInt(key) }
            ?: 1
        val height = master.fieldInt("resOriginalHeight")
            ?: PREVIEW_HEIGHT_KEYS.firstNotNullOfOrNull { key -> master.fieldInt(key) }
            ?: 1
        val masterRevision = master.string("recordChangeTag").orEmpty()
        val assetRevision = asset.string("recordChangeTag").orEmpty()
        val capturedAt = asset.fieldEpochMillis("assetDate")
            ?: (if (legacy) master.fieldEpochMillis("originalCreationDate") ?: asset.fieldEpochMillis("addedDate") else null)
            ?: return null

        return CloudPhotoAsset(
            assetRecordName = assetRecordName,
            isTrashed = trashed,
            byteCount = ((master.fieldValue("resOriginalRes") as? JsonObject)?.get("size") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
                ?: (master.fieldValue("resOriginalFileSize") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 },
            fileName = master.decodedFileName() ?: assetRecordName,
            capturedAtEpochMillis = capturedAt,
            addedAtEpochMillis = asset.fieldEpochMillis("addedDate")?.takeIf { it > 0 },
            width = width.coerceAtLeast(1),
            height = height.coerceAtLeast(1),
            sourceRevision = "$masterRevision:$assetRevision",
            previewDownloadUrl = previewUrl,
            displayDownloadUrl = displayUrl,
            originalDownloadUrl = originalUrl,
            masterRecordName = master.string("recordName")!!,
            kind = when { movie -> dev.mela.engine.model.MediaKind.VIDEO; motionUrl != null -> dev.mela.engine.model.MediaKind.LIVE_PHOTO; else -> dev.mela.engine.model.MediaKind.PHOTO },
            motionDownloadUrl = motionUrl,
            motionMimeType = if (master.fieldString("resOriginalVidComplFileType") == "public.mpeg-4") "video/mp4" else "video/quicktime",
            mimeType = when (master.fieldString("itemType") ?: master.fieldString("resOriginalFileType")) {
                "public.heic", "public.heif" -> "image/heic"
                "public.png" -> "image/png"
                "org.webmproject.webp" -> "image/webp"
                "com.apple.quicktime-movie" -> "video/quicktime"
                else -> if (movie) "video/mp4" else "image/jpeg"
            },
            // CloudKit CPLAsset duration is an INT64 count of milliseconds.
            durationMillis = (asset.fieldValue("duration") as? JsonPrimitive)?.longOrNull,
            resourceFingerprint = RESOURCE_PREFIXES.mapNotNull { prefix ->
                val fingerprint = master.fieldString("${prefix}Fingerprint")
                    ?: (master.fieldValue("${prefix}Res") as? JsonObject)?.string("fileChecksum")
                fingerprint?.let { "$prefix:$it" }
            }.joinToString("|").ifEmpty { masterRevision },
            playbackUrl = if (movie || motionUrl != null) master.assetUrl("resVidMedRes") ?: master.assetUrl("resVidFullRes") ?: motionUrl ?: originalUrl else originalUrl,
        )
    }

    private fun JsonObject.isMovie(): Boolean {
        val type = fieldString("itemType") ?: fieldString("resOriginalFileType") ?: return false
        return type in MOVIE_TYPES || type.contains("movie", ignoreCase = true) ||
            type.contains("video", ignoreCase = true)
    }

    private fun JsonObject.decodedFileName(): String? {
        val encoded = fieldString("filenameEnc") ?: return null
        val decoded = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return encoded
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(decoded))
                .toString()
        }.getOrNull()?.takeIf(String::isNotBlank) ?: encoded
    }

    private fun JsonObject.assetUrl(fieldName: String): String? =
        (fieldValue(fieldName) as? JsonObject)?.string("downloadURL")

    private fun JsonObject.fieldEpochMillis(fieldName: String): Long? {
        val primitive = fieldValue(fieldName) as? JsonPrimitive ?: return null
        val numeric = primitive.doubleOrNull
        if (numeric != null) {
            val epochMillis = if (kotlin.math.abs(numeric) < SECONDS_TO_MILLIS_CUTOFF) {
                (numeric * 1_000).toLong()
            } else {
                numeric.toLong()
            }
            return epochMillis.takeIf { it > 0L }
        }
        return primitive.contentOrNull
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?.takeIf { it > 0L }
    }

    private fun JsonObject.fieldInt(fieldName: String): Int? =
        (fieldValue(fieldName) as? JsonPrimitive)?.intOrNull

    private fun JsonObject.fieldString(fieldName: String): String? =
        (fieldValue(fieldName) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.fieldValue(fieldName: String): JsonElement? =
        ((this["fields"] as? JsonObject)?.get(fieldName) as? JsonObject)?.get("value")

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun malformed(message: String): Nothing = throw AppleProtocolException(
        AppleProtocolError.MALFORMED_RESPONSE,
        message,
    )

    private companion object {
        const val ASSET_RECORD_TYPE = "CPLAsset"
        const val MASTER_RECORD_TYPE = "CPLMaster"
        const val SECONDS_TO_MILLIS_CUTOFF = 100_000_000_000.0
        val RESOURCE_PREFIXES = listOf("resOriginal", "resJPEGMed", "resJPEGLarge", "resJPEGFull", "resJPEGThumb", "resVidMed", "resVidFull", "resOriginalVidCompl")
        val PREVIEW_RESOURCE_KEYS = listOf(
            "resJPEGThumbRes",
            "resJPEGMedRes",
            "resJPEGLargeRes",
        )
        val PREVIEW_WIDTH_KEYS = listOf(
            "resJPEGMedWidth",
            "resJPEGLargeWidth",
            "resJPEGFullWidth",
            "resJPEGThumbWidth",
        )
        val PREVIEW_HEIGHT_KEYS = listOf(
            "resJPEGMedHeight",
            "resJPEGLargeHeight",
            "resJPEGFullHeight",
            "resJPEGThumbHeight",
        )
        val MOVIE_TYPES = setOf(
            "com.apple.quicktime-movie",
            "public.mpeg-4",
            "com.apple.m4v-video",
        )
    }
}
