package me.lvca.alba.protocol.photos

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import me.lvca.alba.protocol.auth.AppleProtocolError
import me.lvca.alba.protocol.auth.AppleProtocolException
import me.lvca.alba.protocol.field
import me.lvca.alba.protocol.fieldInt
import me.lvca.alba.protocol.fieldText
import me.lvca.alba.protocol.network.AppleEndpointPolicy
import me.lvca.alba.protocol.text
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.Base64

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
    val kind: me.lvca.alba.engine.model.MediaKind,
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
    val isHidden: Boolean = false,
    val details: me.lvca.alba.engine.model.MediaDetails = me.lvca.alba.engine.model.MediaDetails(),
)

/** The engine record for this asset, published under [id]. */
internal fun CloudPhotoAsset.toRemoteMediaRecord(
    id: String,
    accentStartArgb: Long,
    accentEndArgb: Long,
    resourceFingerprint: String = this.resourceFingerprint,
) = me.lvca.alba.engine.source.RemoteMediaRecord(
    id = id,
    fileName = fileName,
    capturedAtEpochMillis = capturedAtEpochMillis,
    width = width,
    height = height,
    sourceRevision = sourceRevision,
    accentStartArgb = accentStartArgb,
    accentEndArgb = accentEndArgb,
    kind = kind,
    mimeType = mimeType,
    durationMillis = durationMillis,
    masterRecordName = masterRecordName,
    assetRecordName = assetRecordName,
    resourceFingerprint = resourceFingerprint,
    motionMimeType = motionMimeType,
    addedAtEpochMillis = addedAtEpochMillis,
    isTrashed = isTrashed,
    byteCount = byteCount,
    isHidden = isHidden,
    details = details,
)

internal data class ParsedCloudPhotoPage(
    val photos: List<CloudPhotoAsset>,
    val logicalAssetCount: Int,
    val syncToken: String?,
    val continuationMarker: String?,
)

internal class CloudKitPhotoResponseParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Hidden assets are dropped unless [includeHidden]; the main feed never lists them, and the Hidden album is read separately. */
    fun parse(body: String, includeTrashed: Boolean = false, legacy: Boolean = false, includeHidden: Boolean = false): ParsedCloudPhotoPage {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }
            .getOrNull()
            ?: malformed("Apple returned an invalid Photos response")
        val serverError = (root["errors"] as? JsonArray)
            ?.firstOrNull()
            ?.let { it as? JsonObject }
        if (serverError != null) {
            val reason = serverError.text("reason")
                ?: serverError.text("message")
                ?: "Apple rejected the Photos request"
            throw AppleProtocolException(AppleProtocolError.PHOTOS_UNAVAILABLE, reason)
        }

        val records = (root["records"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        val masters = records.filter { it.text("recordType") == MASTER_RECORD_TYPE }.associateBy { it.text("recordName") }
        val pairedRecords = records.mapNotNull { asset ->
            if (asset.text("recordType") != ASSET_RECORD_TYPE) return@mapNotNull null
            val masterName = (asset.field("masterRef") as? JsonObject)?.text("recordName") ?: return@mapNotNull null
            val master = masters[masterName] ?: return@mapNotNull null
            master to asset
        }
        val photos = pairedRecords.mapNotNull { (master, asset) -> parsePhoto(master, asset, includeTrashed, legacy) }
            .filter { includeHidden || !it.isHidden }

        return ParsedCloudPhotoPage(
            photos = photos,
            logicalAssetCount = pairedRecords.size,
            syncToken = root.text("syncToken"),
            continuationMarker = root.text("continuationMarker"),
        )
    }

    private fun parsePhoto(master: JsonObject, asset: JsonObject, includeTrashed: Boolean, legacy: Boolean): CloudPhotoAsset? {
        val assetRecordName = asset.text("recordName") ?: return null
        val trashed = asset.fieldText("isDeleted") in setOf("1", "true")
        val hidden = asset.fieldText("isHidden") in setOf("1", "true")
        if (asset.fieldText("isExpunged") in setOf("1", "true") || (trashed && !includeTrashed)) return null
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
        val masterRevision = master.text("recordChangeTag").orEmpty()
        val assetRevision = asset.text("recordChangeTag").orEmpty()
        val capturedAt = asset.fieldEpochMillis("assetDate")
            ?: (if (legacy) master.fieldEpochMillis("originalCreationDate") ?: asset.fieldEpochMillis("addedDate") else null)
            ?: return null

        return CloudPhotoAsset(
            assetRecordName = assetRecordName,
            isTrashed = trashed,
            byteCount = ((master.field("resOriginalRes") as? JsonObject)?.get("size") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
                ?: (master.field("resOriginalFileSize") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 },
            fileName = master.decodedFileName() ?: assetRecordName,
            capturedAtEpochMillis = capturedAt,
            addedAtEpochMillis = asset.fieldEpochMillis("addedDate")?.takeIf { it > 0 },
            width = width.coerceAtLeast(1),
            height = height.coerceAtLeast(1),
            sourceRevision = "$masterRevision:$assetRevision",
            previewDownloadUrl = previewUrl,
            displayDownloadUrl = displayUrl,
            originalDownloadUrl = originalUrl,
            masterRecordName = master.text("recordName")!!,
            kind = when { movie -> me.lvca.alba.engine.model.MediaKind.VIDEO; motionUrl != null -> me.lvca.alba.engine.model.MediaKind.LIVE_PHOTO; else -> me.lvca.alba.engine.model.MediaKind.PHOTO },
            motionDownloadUrl = motionUrl,
            motionMimeType = if (master.fieldText("resOriginalVidComplFileType") == "public.mpeg-4") "video/mp4" else "video/quicktime",
            mimeType = originalMimeType(master.fieldText("itemType") ?: master.fieldText("resOriginalFileType"), movie),
            isHidden = hidden,
            details = MediaDetailsParser.parse(asset),
            // CloudKit CPLAsset duration is an INT64 count of milliseconds.
            durationMillis = (asset.field("duration") as? JsonPrimitive)?.longOrNull,
            resourceFingerprint = RESOURCE_PREFIXES.mapNotNull { prefix ->
                val fingerprint = master.fieldText("${prefix}Fingerprint")
                    ?: (master.field("${prefix}Res") as? JsonObject)?.text("fileChecksum")
                fingerprint?.let { "$prefix:$it" }
            }.joinToString("|").ifEmpty { masterRevision },
            playbackUrl = if (movie || motionUrl != null) master.assetUrl("resVidMedRes") ?: master.assetUrl("resVidFullRes") ?: motionUrl ?: originalUrl else originalUrl,
        )
    }

    private fun originalMimeType(type: String?, movie: Boolean): String = ORIGINAL_MIME_TYPES[type]
        ?: if (type?.contains("raw-image") == true) "image/x-raw" else if (movie) "video/mp4" else "image/jpeg"

    private fun JsonObject.isMovie(): Boolean {
        val type = fieldText("itemType") ?: fieldText("resOriginalFileType") ?: return false
        return type in MOVIE_TYPES || type.contains("movie", ignoreCase = true) ||
            type.contains("video", ignoreCase = true)
    }

    private fun JsonObject.decodedFileName(): String? {
        val encoded = fieldText("filenameEnc") ?: return null
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
        (field(fieldName) as? JsonObject)?.text("downloadURL")

    private fun JsonObject.fieldEpochMillis(fieldName: String): Long? {
        val primitive = field(fieldName) as? JsonPrimitive ?: return null
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

        /**
         * Original UTIs to MIME types. RAW UTIs come from icloudpd and pyicloud's item type tables; ProRAW is Adobe DNG.
         * A RAW original must never be labelled as the JPEG preview iCloud renders from it.
         */
        val ORIGINAL_MIME_TYPES = mapOf(
            "public.jpeg" to "image/jpeg",
            "public.heic" to "image/heic",
            "public.heif" to "image/heic",
            "public.png" to "image/png",
            "public.tiff" to "image/tiff",
            "com.compuserve.gif" to "image/gif",
            "org.webmproject.webp" to "image/webp",
            "com.adobe.raw-image" to "image/x-adobe-dng",
            "com.canon.cr2-raw-image" to "image/x-canon-cr2",
            "com.canon.cr3-raw-image" to "image/x-canon-cr3",
            "com.canon.crw-raw-image" to "image/x-canon-crw",
            "com.fuji.raw-image" to "image/x-fuji-raf",
            "com.nikon.raw-image" to "image/x-nikon-nef",
            "com.nikon.nrw-raw-image" to "image/x-nikon-nrw",
            "com.olympus.raw-image" to "image/x-olympus-orf",
            "com.olympus.or-raw-image" to "image/x-olympus-orf",
            "com.panasonic.rw2-raw-image" to "image/x-panasonic-rw2",
            "com.pentax.raw-image" to "image/x-pentax-pef",
            "com.sony.arw-raw-image" to "image/x-sony-arw",
            "com.apple.quicktime-movie" to "video/quicktime",
            "public.mpeg-4" to "video/mp4",
        )
        val MOVIE_TYPES = setOf(
            "com.apple.quicktime-movie",
            "public.mpeg-4",
            "com.apple.m4v-video",
        )
    }
}
