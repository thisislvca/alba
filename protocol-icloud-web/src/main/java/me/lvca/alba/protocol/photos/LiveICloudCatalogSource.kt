package me.lvca.alba.protocol.photos

import kotlinx.serialization.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.lvca.alba.engine.model.*
import me.lvca.alba.engine.source.*
import me.lvca.alba.engine.source.CloudCatalogPage
import me.lvca.alba.engine.source.CloudCatalogSource
import me.lvca.alba.engine.source.RemoteMediaRecord
import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.auth.AppleProtocolError
import me.lvca.alba.protocol.auth.AppleProtocolException
import me.lvca.alba.protocol.fieldFlag
import me.lvca.alba.protocol.fieldInt
import me.lvca.alba.protocol.fieldLong
import me.lvca.alba.protocol.fieldObject
import me.lvca.alba.protocol.fieldText
import me.lvca.alba.protocol.isRemovedFromLibrary
import me.lvca.alba.protocol.network.AppleClientProfile.addWebClientBuild
import me.lvca.alba.protocol.network.AppleHttpRequest
import me.lvca.alba.protocol.network.AppleHttpResponse
import me.lvca.alba.protocol.network.AppleHttpTransport
import me.lvca.alba.protocol.network.AppleMediaHttpException
import me.lvca.alba.protocol.photos.CloudKitErrors.serverErrorCode
import me.lvca.alba.protocol.recordName
import me.lvca.alba.protocol.recordType
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class LiveICloudCatalogSource(
    private val transport: AppleHttpTransport,
    private val sessionProvider: () -> AppleSessionSnapshot?,
    private val isAuthorized: () -> Boolean = { false },
    private val sharedJournal: UploadJournal? = null,
    private val onSessionUpdated: suspend (AppleSessionSnapshot) -> Unit = {},
    private val onSessionRejected: suspend (AppleSessionSnapshot) -> Unit = {},
) : CloudCatalogSource {
    private val cloudKit = CloudKitClient(transport, sessionProvider, onSessionUpdated, onSessionRejected).apply {
        onAccountChanged { downloads.clear(); currentRefreshIds.clear() }
    }
    private val shared = SharedAlbumsCatalog(transport, cloudKit, ::requireSession, { accountLabel }, isAuthorized, sharedJournal)
    private val database = PersonalLibraryDatabase(cloudKit, ::requireSession, isAuthorized)
    private val edits = PersonalLibraryEdits(database, ::requireSession, isAuthorized)
    override suspend fun setFavorite(mediaId: String, favorite: Boolean) = edits.setFavorite(mediaId, favorite)
    override suspend fun createAlbum(name: String) = edits.createAlbum(name)
    override suspend fun deleteAlbum(id: String) = edits.deleteAlbum(id)
    override suspend fun renameAlbum(id: String, name: String) = edits.renameAlbum(id, name)
    override suspend fun addToAlbum(id: String, mediaIds: List<String>) = edits.addToAlbum(id, mediaIds)
    override suspend fun removeFromAlbum(id: String, mediaIds: List<String>) = edits.removeFromAlbum(id, mediaIds)
    override suspend fun setCloudTrashed(mediaIds: List<String>, trashed: Boolean) = edits.setCloudTrashed(mediaIds, trashed)
    override suspend fun setHidden(mediaIds: List<String>, hidden: Boolean) = edits.setHidden(mediaIds, hidden)
    override suspend fun saveLegacySharedPhoto(mediaId: String, fileName: String, source: me.lvca.alba.engine.model.OneShotUploadSource) = shared.saveLegacySharedPhoto(mediaId, fileName, source)
    override suspend fun sharedActivity(id: String, rank: Int) = shared.sharedActivity(id, rank)
    override suspend fun sharedPostDiscussion(id: String) = shared.sharedPostDiscussion(id)
    override suspend fun sharedPostPhotos(id: String) = shared.sharedPostPhotos(id)
    override suspend fun pendingSharedInvitations() = shared.pendingSharedInvitations()
    override suspend fun resolveSharedInvitation(url: String) = shared.resolveSharedInvitation(url)
    override suspend fun respondSharedInvitation(id: String, accept: Boolean, operationId: String) = shared.respondSharedInvitation(id, accept, operationId)
    override suspend fun sharedManagement(id: String) = shared.sharedManagement(id)
    override suspend fun sharedDiscussion(mediaId: String) = shared.sharedDiscussion(mediaId)
    override suspend fun changeSharedAlbum(id: String, command: me.lvca.alba.engine.model.SharedCommand, operationId: String) = shared.changeSharedAlbum(id, command, operationId)
    override suspend fun contributeToSharedAlbum(id: String, mediaIds: List<String>, operationId: String) = shared.contribute(id, mediaIds, operationId)
    override suspend fun uploadSharedPhoto(albumId: String, fileName: String, source: OneShotUploadSource, operationId: String) = shared.upload(albumId, fileName, source, operationId)
    override suspend fun sharedAlbums() = shared.snapshot()
    override suspend fun createSharedAlbum(name: String, operationId: String, generation: me.lvca.alba.engine.model.SharedAlbumGeneration) = shared.create(name, operationId, generation)
    private val parser = CloudKitPhotoResponseParser()
    private val json = Json { ignoreUnknownKeys = true }
    private val downloads = ConcurrentHashMap<String, DownloadReferences>()
    private val currentRefreshIds = ConcurrentHashMap.newKeySet<String>()

    override val writeAccountId: String? get() = sessionProvider()?.let(ICloudAccountIdentity::accountId)
    override val accountLabel: String
        get() = sessionProvider()?.let(ICloudAccountIdentity::namespace) ?: "signed-out"

    override suspend fun captureSyncToken(): String {
        val response = database.call("zones/list", buildJsonObject {})
        return response["zones"]!!.jsonArray.map { it.jsonObject }.first {
            it["zoneID"]!!.jsonObject["zoneName"]!!.jsonPrimitive.content == PrimarySyncZone.NAME
        }["syncToken"]!!.jsonPrimitive.content
    }

    /** Maps [photo] to the engine record and remembers its signed download URLs. */
    private fun mapped(photo: CloudPhotoAsset, namespace: String = accountLabel): RemoteMediaRecord {
        val id = "icloud:$namespace:${photo.assetRecordName}"
        downloads[id] = DownloadReferences(photo.previewDownloadUrl, photo.originalDownloadUrl, photo.playbackUrl, photo.motionDownloadUrl, photo.displayDownloadUrl)
        return photo.toRemoteMediaRecord(id, accent(id, 0), accent(id, 1))
    }
    private suspend fun hydrate(assetNames: Collection<String>, includeTrashed: Boolean = false): List<CloudPhotoAsset> {
        val assets = database.lookup(assetNames)
        if (assets.any { it["serverErrorCode"] != null }) throw CatalogResetRequired()
        val masters = database.lookup(assets.mapNotNull { it.fieldObject("masterRef")?.recordName() })
        if (masters.any { it["serverErrorCode"] != null }) throw CatalogResetRequired()
        // Include hidden assets: a change that hides a photo must flag it, not look like a missing record.
        val parsed = parser.parse(buildJsonObject { put("records", JsonArray(assets + masters)) }.toString(), includeTrashed, includeHidden = true).photos
        if (parsed.size < assets.count { !it.isRemovedFromLibrary() }) throw CatalogResetRequired()
        return parsed
    }
    override suspend fun changes(token: String, known: List<RemoteMediaRecord>): CloudChanges {
        val root = database.call("changes/zone", PrimarySyncZone.changesBody(token, 200))
        val result = root["zones"]!!.jsonArray.single().jsonObject
        result.serverErrorCode()?.let { throw CloudKitErrors.zoneChangesFailure(it) }
        val deleted = mutableSetOf<String>()
        val changedAssets = mutableSetOf<String>()
        for (element in result["records"]!!.jsonArray) {
            val record = element.jsonObject
            val name = record.recordName() ?: throw CatalogResetRequired()
            val type = record.recordType()
            // A newly hidden asset is still in the library: hydrate it and flag it, rather than removing it.
            if (record.isRemovedFromLibrary() && !record.isHiddenAsset()) { deleted += name; continue }
            when (type) {
                "CPLAsset" -> changedAssets += name
                "CPLMaster" -> {
                    val related = known.filter { it.masterRecordName == name }.mapNotNull { it.assetRecordName }
                    if (related.isEmpty()) throw CatalogResetRequired()
                    changedAssets += related
                }
                null -> throw CatalogResetRequired()
            }
        }
        val hydrated = hydrate(changedAssets - deleted)
        val present = hydrated.map { it.assetRecordName }.toSet()
        deleted += changedAssets - present
        return CloudChanges(hydrated.map(::mapped), deleted,
            result["syncToken"]!!.jsonPrimitive.content, result["moreComing"]?.jsonPrimitive?.booleanOrNull == true)
    }
    override suspend fun collections(): CollectionSnapshot {
        val albums = mutableListOf<GalleryCollection>()
        val pending = java.util.LinkedList<String?>().apply { add(null) }
        val visited = mutableSetOf<String?>()
        while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            check(visited.add(parent)) { "Repeated album folder" }
            for (record in database.queryAll("CPLAlbumByPositionLive", if (parent == null) null else "parentId", parent)) {
                if (record.isRemovedFromLibrary()) continue
                val id = record.recordName() ?: continue
                if (albums.any { it.id == id }) continue
                val encoded = record.fieldText("albumNameEnc") ?: continue
                val name = runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrDefault(encoded)
                val folder = record.fieldInt("albumType") == 3
                albums += GalleryCollection(id, name, record.fieldText("parentId") ?: parent,
                    folder, record.fieldLong("position") ?: albums.size.toLong())
                if (folder) pending.add(id)
            }
            check(albums.size < 10000)
        }
        val members = mutableMapOf<String, Set<String>>()
        fun id(name: String) = "icloud:$accountLabel:$name"
        for (album in albums.filterNot { it.isFolder }) {
            members[album.id] = database.queryAll("CPLContainerRelationLiveByAssetDate", "parentId", album.id, true)
                .mapNotNull { it.fieldText("itemId")?.let(::id) }.toSet()
        }
        members[GalleryQuery.FAVORITES] = database.queryAll("CPLAssetAndMasterInSmartAlbumByAssetDate", "smartAlbum", "FAVORITE", true)
            .filter { it.recordType() == "CPLAsset" }.mapNotNull { it.recordName()?.let(::id) }.toSet()
        for (smart in SmartCollection.entries) {
            albums += GalleryCollection(smart.id, smart.title, position = smart.ordinal.toLong())
            val records = if (smart == SmartCollection.BURSTS) database.queryAll("CPLBurstStackAssetAndMasterByAssetDate", ranked = true)
                else database.queryAll("CPLAssetAndMasterInSmartAlbumByAssetDate", "smartAlbum", smart.appleFilter, true)
            members[smart.id] = records.filter { it.recordType() == "CPLAsset" && !it.isRemovedFromLibrary() }
                .mapNotNull { it.recordName()?.let(::id) }.toSet()
        }
        return CollectionSnapshot(albums, members)
    }
    override suspend fun accountProfile(): CloudAccountProfile {
        val session = requireSession()
        cloudKit.bind(session)
        val profile = ICloudAccountProfileService(transport).load(session)
        check(sessionProvider()?.let { it.dsid == session.dsid && it.clientId == session.clientId } == true) {
            "The iCloud account changed while checking the profile."
        }
        cloudKit.persist(session, null)
        return profile
    }

    override suspend fun storageUsage(): CloudStorageUsage {
        val session = requireSession()
        cloudKit.bind(session)
        val response = cloudKit.send(session, AppleHttpRequest("POST", setupUrl("storageUsageInfo", session), PersonalLibraryDatabase.HEADERS))
        check(sessionProvider()?.dsid == session.dsid) { "Account changed while checking storage." }
        return ICloudStorageParser.parse(response.body)
    }

    override suspend fun recentlyDeleted(): List<RemoteMediaRecord> {
        val session = requireSession()
        val records = database.queryAll("CPLAssetAndMasterDeletedByExpungedDate", ranked = true)
        database.requireUnchanged(session)
        check(records.all { it["serverErrorCode"] == null })
        // Hidden photos deleted elsewhere are listed too, as in Photos.
        val parsed = parser.parse(buildJsonObject { put("records", JsonArray(records)) }.toString(), includeTrashed = true, includeHidden = true).photos
        val expected = records.count { it.recordType() == "CPLAsset" && !it.fieldFlag("isExpunged") }
        check(parsed.size == expected) { "Recently Deleted was incomplete. Refresh to try again." }
        return parsed.filter { it.isTrashed }.map(::mapped)
    }

    /** The Hidden album, read from its own index as iCloud.com and pyicloud do (`CPLAssetAndMasterHiddenByAssetDate`). */
    override suspend fun hiddenMedia(): List<RemoteMediaRecord> {
        val session = requireSession()
        val records = database.queryAll(HIDDEN_LIST_RECORD_TYPE, ranked = true)
        database.requireUnchanged(session)
        check(records.all { it["serverErrorCode"] == null })
        val parsed = parser.parse(buildJsonObject { put("records", JsonArray(records)) }.toString(), includeHidden = true).photos
        val expected = records.count { it.recordType() == "CPLAsset" && it.fieldFlag("isHidden") &&
            !it.fieldFlag("isDeleted") && !it.fieldFlag("isExpunged") }
        check(parsed.count { it.isHidden } == expected) { HIDDEN_INCOMPLETE }
        return parsed.filter { it.isHidden && !it.isTrashed }.map(::mapped)
    }

    private suspend fun freshReferences(mediaId: String): DownloadReferences {
        require(mediaId.startsWith("icloud:$accountLabel:")) { "Media belongs to another account" }
        val photo = try { hydrate(listOf(mediaId.substringAfterLast(':')), includeTrashed = true).singleOrNull() }
        catch (_: CatalogResetRequired) { null }
        if (photo == null) throw AppleProtocolException(AppleProtocolError.PHOTOS_UNAVAILABLE, "Media is no longer available")
        mapped(photo)
        return downloads.getValue(mediaId)
    }
    override suspend fun openPlayback(mediaId: String, position: Long, length: Long): MediaRead {
        if (SharedMediaIdentity.isShared(mediaId)) return shared.playback(mediaId, position, length)
        require(mediaId.startsWith("icloud:$accountLabel:"))
        var ref = freshReferences(mediaId)
        return try { transport.openRange(ref.playbackUrl, position, length) }
        catch (expired: AppleMediaHttpException) {
            if (expired.status !in setOf(401, 403, 404, 410)) throw expired
            ref = freshReferences(mediaId)
            transport.openRange(ref.playbackUrl, position, length)
        }
    }

    override suspend fun fetchPage(cursor: String?, limit: Int): CloudCatalogPage {
        require(limit in 1..100) { "iCloud page size must be between 1 and 100" }
        val session = requireSession()
        cloudKit.bind(session)
        val pageCursor = PageCursor.decode(cursor)
        if (cursor == null) {
            preparePhotos(session)
            currentRefreshIds.clear()
        }

        val response = cloudKit.send(
            session = session,
            request = AppleHttpRequest(
                method = "POST",
                url = database.url(session, "records/query"),
                headers = PersonalLibraryDatabase.HEADERS,
                body = photoQueryBody(
                    offset = pageCursor.offset,
                    limit = limit,
                    continuationMarker = pageCursor.continuationMarker,
                ),
            ),
        )
        val parsed = parser.parse(response.body)
        val namespace = ICloudAccountIdentity.namespace(session)
        val records = parsed.photos.map { photo -> mapped(photo, namespace).also { currentRefreshIds += it.id } }
        val accumulatedCount = pageCursor.accumulatedCount + parsed.logicalAssetCount
        val nextOffset = pageCursor.offset + accumulatedCount
        val nextCursor = when {
            parsed.continuationMarker != null -> PageCursor(
                offset = pageCursor.offset,
                continuationMarker = parsed.continuationMarker,
                accumulatedCount = accumulatedCount,
            ).encode()

            accumulatedCount >= limit -> PageCursor(nextOffset).encode()
            else -> null
        }
        if (nextCursor == null) {
            downloads.keys.retainAll(currentRefreshIds)
            currentRefreshIds.clear()
        }

        return CloudCatalogPage(
            records = records,
            nextCursor = nextCursor,
            changeToken = parsed.syncToken
                ?: "rank:$nextOffset:${parsed.photos.lastOrNull()?.sourceRevision.orEmpty()}",
        )
    }

    override suspend fun writeDisplay(mediaId: String, output: OutputStream) {
        if (SharedMediaIdentity.isShared(mediaId)) return shared.write(mediaId, output, "display")
        stream(mediaId, output, DownloadReferences::displayUrl)
    }

    override suspend fun writePreview(mediaId: String, output: OutputStream) {
        if (SharedMediaIdentity.isShared(mediaId)) return shared.write(mediaId, output, "preview")
        stream(mediaId, output, DownloadReferences::previewUrl)
    }

    override suspend fun writeMotion(mediaId: String, output: OutputStream) {
        if (SharedMediaIdentity.isShared(mediaId)) return shared.write(mediaId, output, "motion")
        stream(mediaId, output) { requireNotNull(it.motionUrl) { "Live Photo motion is unavailable" } }
    }

    override suspend fun writeOriginal(mediaId: String, output: OutputStream) {
        if (SharedMediaIdentity.isShared(mediaId)) return shared.write(mediaId, output, "original")
        stream(mediaId, output, DownloadReferences::originalUrl)
    }

    private suspend fun stream(
        mediaId: String,
        output: OutputStream,
        selectUrl: (DownloadReferences) -> String,
    ) {
        val session = requireSession()
        cloudKit.bind(session)
        require(mediaId.startsWith("icloud:$accountLabel:"))
        val references = downloads[mediaId] ?: freshReferences(mediaId)
        check(selectUrl(references).isNotBlank()) { "No poster image available" }
        try { transport.stream(selectUrl(references), output) }
        catch (expired: AppleMediaHttpException) {
            if (expired.status !in setOf(401, 403, 404, 410)) throw expired
            transport.stream(selectUrl(freshReferences(mediaId)), output)
        }
        cloudKit.persist(session, null)
    }

    private suspend fun preparePhotos(session: AppleSessionSnapshot) {
        val webAccess = cloudKit.send(
            session,
            AppleHttpRequest(
                method = "POST",
                url = setupUrl("requestWebAccessState", session),
            ),
        ).jsonObjectOrNull()
        val protectedWebAccessDisabled = webAccess
            ?.get("isICDRSDisabled")
            ?.jsonPrimitive
            ?.contentOrNull == "true"
        if (protectedWebAccessDisabled) {
            throw AppleProtocolException(
                AppleProtocolError.PHOTOS_UNAVAILABLE,
                "Access to iCloud data on the web is disabled for this account.",
            )
        }

        val indexing = cloudKit.send(
            session = session,
            request = AppleHttpRequest(
                method = "POST",
                url = database.url(session, "records/query"),
                headers = PersonalLibraryDatabase.HEADERS,
                body = buildJsonObject {
                    putJsonObject("query") { put("recordType", "CheckIndexingState") }
                    putJsonObject("zoneID") {
                        put("zoneName", PrimarySyncZone.NAME)
                        put("zoneType", PrimarySyncZone.TYPE)
                    }
                    put("resultsLimit", 1)
                }.toString(),
            ),
        )
        val state = indexing.jsonObjectOrNull()
            ?.get("records")
            ?.let { it as? kotlinx.serialization.json.JsonArray }
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.fieldText("state")
        if (state != null && state != "FINISHED") {
            throw AppleProtocolException(
                AppleProtocolError.PHOTOS_UNAVAILABLE,
                "iCloud Photos is still preparing this library. Try again in a few minutes.",
            )
        }
    }

    private fun requireSession(): AppleSessionSnapshot = sessionProvider()
        ?: throw AppleProtocolException(
            AppleProtocolError.SESSION_EXPIRED,
            "Sign in to iCloud to load the live library.",
        )

    private fun setupUrl(path: String, session: AppleSessionSnapshot): String =
        "$SETUP_ENDPOINT/$path".toHttpUrl().newBuilder()
            .addWebClientBuild()
            .addQueryParameter("clientId", session.clientId)
            .addQueryParameter("dsid", session.dsid)
            .build()
            .toString()

    private fun photoQueryBody(
        offset: Int,
        limit: Int,
        continuationMarker: String?,
    ): String = buildJsonObject {
        putJsonObject("query") {
            put("recordType", PHOTO_LIST_RECORD_TYPE)
            putJsonArray("filterBy") {
                add(buildJsonObject {
                    put("fieldName", "direction")
                    put("comparator", "EQUALS")
                    putJsonObject("fieldValue") {
                        put("type", "STRING")
                        put("value", "ASCENDING")
                    }
                })
                add(buildJsonObject {
                    put("fieldName", "startRank")
                    put("comparator", "EQUALS")
                    putJsonObject("fieldValue") {
                        put("type", "INT64")
                        put("value", offset)
                    }
                })
            }
        }
        putJsonObject("zoneID") {
            put("zoneName", PrimarySyncZone.NAME)
            put("zoneType", PrimarySyncZone.TYPE)
        }
        putJsonArray("desiredKeys") {
            PHOTO_DESIRED_KEYS.forEach { add(JsonPrimitive(it)) }
        }
        put("resultsLimit", limit * RECORDS_PER_PHOTO)
        continuationMarker?.let { put("continuationMarker", it) }
    }.toString()

    private fun AppleHttpResponse.jsonObjectOrNull(): JsonObject? = runCatching {
        json.parseToJsonElement(body) as? JsonObject
    }.getOrNull()

    private fun accent(mediaId: String, offset: Int): Long {
        val index = ((mediaId.hashCode().toUInt().toLong() + offset) % ACCENTS.size).toInt()
        return ACCENTS[index]
    }

    private data class DownloadReferences(
        val previewUrl: String,
        val originalUrl: String,
        val playbackUrl: String,
        val motionUrl: String?,
        val displayUrl: String,
    )

    private data class PageCursor(
        val offset: Int,
        val continuationMarker: String? = null,
        val accumulatedCount: Int = 0,
    ) {
        fun encode(): String {
            val marker = continuationMarker
                ?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray()) }
                .orEmpty()
            return "$offset:$accumulatedCount:$marker"
        }

        companion object {
            fun decode(value: String?): PageCursor {
                if (value == null) return PageCursor(0)
                val parts = value.split(':', limit = 3)
                val offset = parts.firstOrNull()?.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid iCloud cursor")
                require(offset >= 0) { "Invalid iCloud cursor" }
                val accumulatedCount = parts.getOrNull(1)?.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid iCloud cursor")
                require(accumulatedCount >= 0) { "Invalid iCloud cursor" }
                val marker = parts.getOrNull(2)
                    ?.takeIf(String::isNotBlank)
                    ?.let { encoded ->
                        runCatching {
                            String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
                        }.getOrElse { throw IllegalArgumentException("Invalid iCloud cursor") }
                    }
                return PageCursor(offset, marker, accumulatedCount)
            }
        }
    }

    private companion object {
        const val SETUP_ENDPOINT = "https://setup.icloud.com/setup/ws/1"
        const val PHOTO_LIST_RECORD_TYPE = "CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted"
        const val HIDDEN_LIST_RECORD_TYPE = "CPLAssetAndMasterHiddenByAssetDate"
        const val HIDDEN_INCOMPLETE = "Hidden was incomplete. Refresh to try again."
        const val RECORDS_PER_PHOTO = 2
        val PHOTO_DESIRED_KEYS = listOf(
            "resOriginalVidComplRes", "resOriginalVidComplFileType", "resOriginalVidComplFingerprint",
            "isHidden", "isDeleted", "isFavorite", "duration", "resOriginalFingerprint", "resVidMedRes", "resVidFullRes",
            "resJPEGMedFingerprint", "resJPEGLargeFingerprint", "resJPEGFullFingerprint", "resJPEGThumbFingerprint",
            "resVidMedFingerprint", "resVidFullFingerprint",
            "filenameEnc",
            "itemType",
            "resOriginalFileType",
            "resOriginalWidth",
            "resOriginalHeight",
            "resOriginalRes",
            "resJPEGMedWidth",
            "resJPEGMedHeight",
            "resJPEGMedRes",
            "resJPEGLargeWidth",
            "resJPEGLargeHeight",
            "resJPEGLargeRes",
            "resJPEGFullWidth",
            "resJPEGFullHeight",
            "resJPEGFullRes",
            "resJPEGThumbWidth",
            "resJPEGThumbHeight",
            "resJPEGThumbRes",
            "masterRef",
            "assetDate", "addedDate",
            // Descriptive fields, named as pyicloud requests them.
            "captionEnc", "extendedDescEnc", "locationEnc", "timeZoneOffset", "burstId",
        )
        val ACCENTS = longArrayOf(
            0xFFB86855L,
            0xFF496F82L,
            0xFF879B72L,
            0xFF7C668EL,
            0xFFD09A57L,
            0xFF506A65L,
        )
    }
}

/** A hidden asset that is otherwise live: [isRemovedFromLibrary] counts it as removed, but it belongs in Hidden. */
private fun JsonObject.isHiddenAsset(): Boolean = recordType() == "CPLAsset" && fieldFlag("isHidden") &&
    this["deleted"]?.jsonPrimitive?.booleanOrNull != true && !fieldFlag("isDeleted") && !fieldFlag("isExpunged")
