package me.lvca.alba.protocol.photos

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.lvca.alba.engine.model.GalleryQuery
import me.lvca.alba.engine.model.SmartCollection
import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.account.PersistedCookie
import me.lvca.alba.protocol.network.AppleHttpRequest
import me.lvca.alba.protocol.network.AppleHttpResponse
import me.lvca.alba.protocol.network.AppleHttpTransport
import org.junit.Assert.*
import org.junit.Test
import java.io.OutputStream
import java.util.Base64

/**
 * Albums read from payloads shaped like a real library's: Photos' two unnamed root folders, albums at the top level with
 * no `parentId`, album relations that come back as master, asset and relation records, and smart albums of thousands of
 * photos. CloudKit returns every field of each record unless the query names `desiredKeys`; a photo's two full records
 * are about 15 KB, so a membership read that asked for everything ran a real library out of memory.
 */
class LiveAlbumsTest {
    private val session = AppleSessionSnapshot("test@example.com", "client", "IT", "session", "token", null,
        "123", mapOf("ckdatabasews" to "https://p01-ckdatabasews.icloud.com"), emptyList())

    @Test fun topLevelAlbumsAreListedOutsideApplesRootFoldersWithEveryMember() = runBlocking {
        val sizes = listOf(27, 91, 465, 1, 0)
        val transport = Library(sizes, smartSize = 250)
        val source = LiveICloudCatalogSource(transport, { session })
        val result = source.collections()

        val albums = result.collections.filterNot { it.id.startsWith("smart:") }
        assertEquals(sizes.indices.map { "album-$it" }, albums.map { it.id })
        assertEquals(sizes.indices.map { "Album $it" }, albums.map { it.name })
        assertTrue(albums.none { it.isFolder || it.parentId != null })
        sizes.forEachIndexed { index, size ->
            assertEquals(size, result.members.getValue("album-$index").size)
            assertTrue(result.members.getValue("album-$index").all { it.startsWith("icloud:${source.accountLabel}:asset-") })
        }
        assertEquals(250, result.members.getValue(SmartCollection.LIVE_PHOTOS.id).size)
        assertEquals(250, result.members.getValue(GalleryQuery.FAVORITES).size)
    }

    @Test fun membershipReadsAskOnlyForTheFieldsTheyUse() = runBlocking {
        val transport = Library(listOf(465), smartSize = 250)
        LiveICloudCatalogSource(transport, { session }).collections()

        val membership = transport.queries.filter { it.type in MEMBERSHIP_QUERIES }
        assertTrue(membership.isNotEmpty())
        assertEquals("full asset and master records must not be read for membership", emptyList<String>(),
            membership.filter { it.desiredKeys == null }.map { it.type }.distinct())
        // A page of the largest read stays small: names and flags only, no resource fields or download URLs.
        assertTrue(transport.largestMembershipPage < 100_000)
    }

    @Test fun aHiddenOrDeletedPhotoInASmartAlbumIsLeftOut() = runBlocking {
        val transport = Library(emptyList(), smartSize = 3, removed = setOf("asset-1"))
        val result = LiveICloudCatalogSource(transport, { session }).collections()
        assertEquals(setOf("asset-0", "asset-2"), result.members.getValue(SmartCollection.VIDEOS.id).map { it.substringAfterLast(':') }.toSet())
    }

    private data class Query(val type: String, val desiredKeys: List<String>?)

    /** A library answering like CloudKit: 200 records a page with a continuation marker, all fields unless asked for some. */
    private class Library(sizes: List<Int>, private val smartSize: Int, private val removed: Set<String> = emptySet()) : AppleHttpTransport {
        val queries = mutableListOf<Query>()
        var largestMembershipPage = 0
        private val albums = sizes.mapIndexed { index, size -> "album-$index" to size }.toMap()

        private fun albumRecord(name: String, type: Int, title: String?, position: Long) = buildJsonObject {
            put("recordName", name); put("recordType", "CPLAlbum"); put("deleted", false)
            putJsonObject("fields") {
                putJsonObject("albumType") { put("value", type); put("type", "INT64") }
                putJsonObject("position") { put("value", position); put("type", "INT64") }
                putJsonObject("sortType") { put("value", 0); put("type", "INT64") }
                title?.let { name ->
                    putJsonObject("albumNameEnc") { put("value", Base64.getEncoder().encodeToString(name.toByteArray())); put("type", "ENCRYPTED_BYTES") }
                }
            }
        }

        /** Every field of a real photo's records, or only [keys] of them, as CloudKit answers `desiredKeys`. */
        private fun fields(all: Map<String, JsonElement>, keys: List<String>?) = JsonObject(if (keys == null) all else all.filterKeys { it in keys })

        private fun resourceFields(index: Int): Map<String, JsonElement> = (RESOURCES.flatMap { kind ->
            listOf("${kind}Res" to buildJsonObject { putJsonObject("value") {
                put("fileChecksum", "checksum-$kind-$index".padEnd(44, 'x')); put("size", 1_000_000)
                put("wrappingKey", "wrapping-key-$index".padEnd(24, 'k')); put("referenceChecksum", "reference-$index".padEnd(44, 'r'))
                put("downloadURL", "https://cvws.icloud-content.com/B/${"x".repeat(180)}/\${f}?o=${"y".repeat(120)}")
            } }, "${kind}Fingerprint" to buildJsonObject { put("value", "fingerprint-$index".padEnd(28, 'f')) },
                "${kind}Width" to buildJsonObject { put("value", 4032) }, "${kind}Height" to buildJsonObject { put("value", 3024) })
        }).toMap()

        private fun photo(index: Int, keys: List<String>?): List<JsonObject> {
            val asset = "asset-$index"
            val assetFields = mutableMapOf<String, JsonElement>(
                "masterRef" to buildJsonObject { putJsonObject("value") { put("recordName", "master-$index") } },
                "assetDate" to buildJsonObject { put("value", 1_700_000_000_000L + index) },
                "isHidden" to buildJsonObject { put("value", 0) },
                "captionEnc" to buildJsonObject { put("value", "c".repeat(200)) },
            )
            if (asset in removed) assetFields["isDeleted"] = buildJsonObject { put("value", 1) }
            return listOf(
                buildJsonObject { put("recordName", "master-$index"); put("recordType", "CPLMaster"); put("fields", fields(resourceFields(index), keys)) },
                buildJsonObject { put("recordName", asset); put("recordType", "CPLAsset"); put("fields", fields(assetFields, keys)) },
            )
        }

        private fun relation(album: String, index: Int, keys: List<String>?) = buildJsonObject {
            put("recordName", "$album-relation-$index"); put("recordType", "CPLContainerRelation")
            put("fields", fields(mapOf("itemId" to buildJsonObject { put("value", "asset-$index") },
                "position" to buildJsonObject { put("value", index) }, "isKeyAsset" to buildJsonObject { put("value", 0) }), keys))
        }

        /** The records of one query, before paging. An album relation comes back with its photo's master and asset. */
        private fun records(type: String, filters: Map<String, JsonElement>, keys: List<String>?): List<JsonObject> = when (type) {
            "CPLAlbumByPositionLive" -> if (filters["parentId"] != null) emptyList() else
                listOf(albumRecord("----Project-Root-Folder----", 3, null, 0), albumRecord("----Root-Folder----", 3, null, 0)) +
                    albums.keys.mapIndexed { index, name -> albumRecord(name, 0, "Album $index", 1_051_392L + index) }
            "CPLContainerRelationLiveByAssetDate" -> {
                val album = filters.getValue("parentId").jsonPrimitive.content
                (0 until albums.getValue(album)).flatMap { photo(it, keys) + relation(album, it, keys) }
            }
            "CPLAssetAndMasterInSmartAlbumByAssetDate", "CPLBurstStackAssetAndMasterByAssetDate" -> (0 until smartSize).flatMap { photo(it, keys) }
            else -> emptyList()
        }

        override suspend fun execute(request: AppleHttpRequest): AppleHttpResponse {
            val body = Json.parseToJsonElement(request.body ?: "{}").jsonObject
            val query = body["query"]?.jsonObject ?: return AppleHttpResponse(200, emptyMap(), """{"records":[]}""")
            val type = query["recordType"]!!.jsonPrimitive.content
            val filters = query["filterBy"]?.jsonArray.orEmpty().associate {
                it.jsonObject["fieldName"]!!.jsonPrimitive.content to it.jsonObject["fieldValue"]!!.jsonObject["value"]!!
            }
            val keys = body["desiredKeys"]?.jsonArray?.map { it.jsonPrimitive.content }
            queries += Query(type, keys)
            // A ranked query starts at its rank; the continuation marker carries the position within it.
            val rank = filters["startRank"]?.jsonPrimitive?.int ?: 0
            val all = records(type, filters, keys)
            val perPhoto = if (type == "CPLContainerRelationLiveByAssetDate") 3 else 2
            val start = rank * perPhoto / (if (type == "CPLContainerRelationLiveByAssetDate") 2 else 1) +
                (body["continuationMarker"]?.jsonPrimitive?.int ?: 0)
            val page = all.drop(start).take(PAGE)
            val more = start + PAGE < all.size
            val response = buildJsonObject {
                put("records", JsonArray(page))
                if (more) put("continuationMarker", JsonPrimitive((body["continuationMarker"]?.jsonPrimitive?.int ?: 0) + PAGE).content)
            }.toString()
            if (type in MEMBERSHIP_QUERIES) largestMembershipPage = maxOf(largestMembershipPage, response.length)
            return AppleHttpResponse(200, emptyMap(), response)
        }

        override suspend fun stream(url: String, output: OutputStream) = Unit
        override fun snapshotCookies() = emptyList<PersistedCookie>()
        override fun restore(snapshot: AppleSessionSnapshot) = Unit
        override fun clear() = Unit
    }

    private companion object {
        const val PAGE = 200
        val MEMBERSHIP_QUERIES = setOf("CPLContainerRelationLiveByAssetDate", "CPLAssetAndMasterInSmartAlbumByAssetDate",
            "CPLBurstStackAssetAndMasterByAssetDate")
        val RESOURCES = listOf("resOriginal", "resJPEGMed", "resJPEGThumb", "resJPEGFull", "resVidMed", "resVidSmall", "resOriginalVidCompl")
    }
}
