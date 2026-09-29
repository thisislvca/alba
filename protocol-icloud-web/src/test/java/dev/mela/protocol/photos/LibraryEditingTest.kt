package dev.mela.protocol.photos

import dev.mela.engine.model.MediaKind
import dev.mela.engine.model.SmartCollection
import dev.mela.protocol.account.*
import dev.mela.protocol.network.*
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LibraryEditingTest {
    private val session = AppleSessionSnapshot("test@example.invalid", "client", "IT", "session", "token", null,
        "123", mapOf("ckdatabasews" to "https://p01-ckdatabasews.icloud.com"), emptyList())

    @Test fun favoriteUsesFreshVersionAndOneShotRequestAndChecksReturnedValue() = runBlocking {
        val transport = EditingTransport()
        val source = LiveICloudCatalogSource(transport, { session }, isAuthorized = { true })
        val id = "icloud:${source.accountLabel}:photo"
        source.setFavorite(id, true)
        val request = transport.requests.single { it.url.contains("records/modify") }
        assertTrue(request.oneShot)
        val body = Json.parseToJsonElement(request.body!!).jsonObject
        assertTrue(body["atomic"]!!.jsonPrimitive.boolean)
        val record = body["operations"]!!.jsonArray.single().jsonObject["record"]!!.jsonObject
        assertEquals("current-version", record["recordChangeTag"]!!.jsonPrimitive.content)
        assertEquals(1, record["fields"]!!.jsonObject["isFavorite"]!!.jsonObject["value"]!!.jsonPrimitive.int)
        transport.omitFavoriteConfirmation = true
        assertTrue(runCatching { source.setFavorite(id, false) }.isFailure)
        assertEquals(2, transport.requests.count { it.url.contains("records/modify") })
    }

    @Test fun deletingAlbumTombstonesOnlyAlbumAndRequiresConfirmationWithoutRetry() = runBlocking {
        val transport = EditingTransport()
        val source = LiveICloudCatalogSource(transport, { session }, isAuthorized = { true })
        source.deleteAlbum("album")
        val write = transport.requests.single { it.url.contains("records/modify") }
        assertTrue(write.oneShot)
        val operation = Json.parseToJsonElement(write.body!!).jsonObject["operations"]!!.jsonArray.single().jsonObject
        val record = operation["record"]!!.jsonObject
        assertEquals("update", operation["operationType"]!!.jsonPrimitive.content)
        assertEquals("CPLAlbum", record["recordType"]!!.jsonPrimitive.content)
        assertEquals("album", record["recordName"]!!.jsonPrimitive.content)
        assertEquals(setOf("isDeleted"), record["fields"]!!.jsonObject.keys)
        assertEquals("current-version", record["recordChangeTag"]!!.jsonPrimitive.content)
        transport.omitFavoriteConfirmation = true
        assertTrue(runCatching { source.deleteAlbum("album") }.isFailure)
        transport.omitFavoriteConfirmation = false
        transport.disconnect = true
        assertTrue(runCatching { source.deleteAlbum("album") }.isFailure)
        assertEquals(3, transport.requests.count { it.url.contains("records/modify") })
        assertTrue(runCatching { source.deleteAlbum("smart:videos") }.isFailure)
    }

    @Test fun offlineWrongAccountAndAccountSwitchNeverSendAnEdit() = runBlocking {
        val transport = EditingTransport()
        var active = session
        var authorized = false
        val source = LiveICloudCatalogSource(transport, { active }, { authorized })
        val id = "icloud:${source.accountLabel}:photo"
        assertTrue(runCatching { source.setFavorite(id, true) }.isFailure)
        assertTrue(transport.requests.isEmpty())
        authorized = true
        assertTrue(runCatching { source.setFavorite("icloud:someone-else:photo", true) }.isFailure)
        transport.afterLookup = { active = session.copy(dsid = "456") }
        assertTrue(runCatching { source.setFavorite(id, true) }.isFailure)
        assertFalse(transport.requests.any { it.url.contains("records/modify") })
    }

    @Test fun albumCreateRenameAndIdempotentMembershipUsePersonalZone() = runBlocking {
        val transport = EditingTransport()
        val source = LiveICloudCatalogSource(transport, { session }, isAuthorized = { true })
        val album = source.createAlbum("Summer ☀")
        assertEquals("Summer ☀", album.name)
        assertTrue(album.id.matches(Regex("[A-F0-9]{32}")))
        source.renameAlbum("album", "Renamed")
        val id = "icloud:${source.accountLabel}:photo"
        source.addToAlbum("album", listOf(id, id))
        source.addToAlbum("album", listOf(id))
        val writes = transport.requests.filter { it.url.contains("records/modify") }
        assertEquals(3, writes.size)
        assertTrue(writes.all { it.oneShot && it.body!!.contains("PrimarySync") })
        assertTrue(writes.last().body!!.contains("photo-IN-album"))
        assertTrue(writes.last().body!!.contains("CPLContainerRelation"))
        assertTrue(runCatching { source.renameAlbum("smart:videos", "No") }.isFailure)
    }

    @Test fun recordErrorsAndNetworkUncertaintyAreNotReportedAsSuccessOrRetried() = runBlocking {
        val transport = EditingTransport()
        val source = LiveICloudCatalogSource(transport, { session }, isAuthorized = { true })
        transport.reject = true
        assertTrue(runCatching { source.createAlbum("Trip") }.isFailure)
        assertEquals(1, transport.requests.count { it.url.contains("records/modify") })
        transport.reject = false
        transport.disconnect = true
        assertTrue(runCatching { source.createAlbum("Trip") }.isFailure)
        assertEquals(2, transport.requests.count { it.url.contains("records/modify") })
    }

    @Test fun smartCollectionsUseUpstreamIndexesIncludingSeparateBurstIndex() = runBlocking {
        val transport = EditingTransport()
        val source = LiveICloudCatalogSource(transport, { session }, isAuthorized = { true })
        val result = source.collections()
        assertTrue(result.collections.map { it.id }.containsAll(SmartCollection.entries.map { it.id }))
        val bodies = transport.requests.mapNotNull { it.body }
        assertTrue(bodies.any { it.contains("CPLBurstStackAssetAndMasterByAssetDate") })
        SmartCollection.entries.filter { it.appleFilter != null }.forEach { smart ->
            assertTrue(bodies.any { it.contains("\"value\":\"${smart.appleFilter}\"") })
        }
        assertFalse(bodies.any { it.contains("HIDDEN") || it.contains("shared") })
    }

    @Test fun livePhotoKeepsStillAndMotionResourcesSeparateAndInvalidatesChangedMotion() {
        fun page(fingerprint: String) = """{"records":[
            {"recordName":"photo","recordType":"CPLAsset","recordChangeTag":"a1","fields":{"masterRef":{"value":{"recordName":"master"}},"assetDate":{"value":1777777777000},"addedDate":{"value":1778888888000}}},
            {"recordName":"master","recordType":"CPLMaster","recordChangeTag":"m1","fields":{
                "itemType":{"value":"public.heic"},"filenameEnc":{"value":"SU1HXzEuSEVJQw=="},
                "resOriginalFingerprint":{"value":"still"},"resOriginalRes":{"value":{"downloadURL":"https://cws.icloud-content.com/still"}},
                "resOriginalVidComplFileType":{"value":"com.apple.quicktime-movie"},
                "resOriginalVidComplFingerprint":{"value":"$fingerprint"},
                "resOriginalVidComplRes":{"value":{"downloadURL":"https://cws.icloud-content.com/motion"}},
                "resVidMedRes":{"value":{"downloadURL":"https://cws.icloud-content.com/stream"}}
            }}]}"""
        val photo = CloudKitPhotoResponseParser().parse(page("motion-1")).photos.single()
        assertEquals(1778888888000L, photo.addedAtEpochMillis)
        assertEquals(1777777777000L, photo.capturedAtEpochMillis)
        assertEquals(MediaKind.LIVE_PHOTO, photo.kind)
        assertEquals("image/heic", photo.mimeType)
        assertEquals("video/quicktime", photo.motionMimeType)
        assertEquals("https://cws.icloud-content.com/still", photo.originalDownloadUrl)
        assertEquals("https://cws.icloud-content.com/motion", photo.motionDownloadUrl)
        assertEquals("https://cws.icloud-content.com/stream", photo.playbackUrl)
        assertNotEquals(photo.resourceFingerprint, CloudKitPhotoResponseParser().parse(page("motion-2")).photos.single().resourceFingerprint)
    }

    @Test fun storageIsValidatedAndOverQuotaDoesNotOverflowProgress() {
        val storage = ICloudStorageParser.parse("""{"storageUsageInfo":{"usedStorageInBytes":600,"totalStorageInBytes":500},"storageUsageByMedia":[{"displayLabel":"Photos","usageInBytes":550}]}""", 10)
        assertEquals(600L, storage.usedBytes)
        assertEquals(500L, storage.totalBytes)
        assertEquals(1f, storage.fraction)
        assertEquals(10L, storage.checkedAtEpochMillis)
        assertEquals("Photos", storage.categories.single().name)
        assertTrue(runCatching { ICloudStorageParser.parse("{}") }.isFailure)
        assertTrue(runCatching { ICloudStorageParser.parse("""{"storageUsageInfo":{"usedStorageInBytes":0,"totalStorageInBytes":0}}""") }.isFailure)
    }

    private class EditingTransport : AppleHttpTransport {
        val requests = mutableListOf<AppleHttpRequest>()
        val added = mutableSetOf<String>()
        var omitFavoriteConfirmation = false
        var reject = false
        var disconnect = false
        var afterLookup: () -> Unit = {}
        override val sessionHeaders = AppleSessionHeaders()
        override suspend fun execute(request: AppleHttpRequest): AppleHttpResponse {
            requests += request
            val body = Json.parseToJsonElement(request.body ?: "{}").jsonObject
            val records = when {
                request.url.contains("records/lookup") -> body["records"]!!.jsonArray.map { entry ->
                    val name = entry.jsonObject["recordName"]!!.jsonPrimitive.content
                    buildJsonObject {
                        put("recordName", name)
                        if ("-IN-" in name && name !in added) put("serverErrorCode", "NOT_FOUND") else {
                            put("recordType", if (name == "album") "CPLAlbum" else if ("-IN-" in name) "CPLContainerRelation" else "CPLAsset")
                            put("recordChangeTag", "current-version")
                            putJsonObject("fields") { putJsonObject("albumType") { put("value", 0) } }
                        }
                    }
                }.also { afterLookup() }
                request.url.contains("records/modify") -> {
                    if (disconnect) throw java.io.IOException("Connection dropped")
                    body["operations"]!!.jsonArray.map { op ->
                        val record = op.jsonObject["record"]!!.jsonObject
                        added += record["recordName"]!!.jsonPrimitive.content
                        if (reject) buildJsonObject { put("recordName", record["recordName"]!!); put("serverErrorCode", "CONFLICT") }
                        else if (omitFavoriteConfirmation) JsonObject(record.toMutableMap().apply { remove("fields") })
                        else record
                    }
                }
                else -> emptyList()
            }
            return AppleHttpResponse(200, emptyMap(), buildJsonObject { put("records", JsonArray(records)) }.toString())
        }
        override suspend fun stream(url: String, output: OutputStream) = Unit
        override fun restore(snapshot: AppleSessionSnapshot) = Unit
        override fun clear() = Unit
        override fun snapshotCookies() = emptyList<PersistedCookie>()
    }
}
