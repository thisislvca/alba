package dev.mela.protocol.photos

import dev.mela.engine.source.CatalogResetRequired
import dev.mela.engine.model.*
import dev.mela.protocol.account.*
import dev.mela.protocol.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.*
import org.junit.Assert.*
import org.junit.Test

class CatalogFeaturesTest {
    private val session = AppleSessionSnapshot("test@example.com", "client", "IT", "session", "token", null,
        "123", mapOf("ckdatabasews" to "https://p01-ckdatabasews.icloud.com"), emptyList())
    private fun source(t: Transport) = LiveICloudCatalogSource(t, { session })

    @Test fun mapsVideoAndHydratesResourcesAfterRestartWithOneExpiryRefresh() = runBlocking {
        val t = Transport()
        val item = source(t).fetchPage(null, 100).records.single()
        assertEquals(MediaKind.VIDEO, item.kind)
        assertEquals(3000L, item.durationMillis)
        val restarted = source(t)
        t.expire = true
        restarted.openPlayback(item.id, 100, 4).use { assertEquals("data", it.input.reader().readText()) }
        assertEquals(2, t.opens)
        assertTrue(t.lookups >= 4)
        assertEquals("https://cws.icloud-content.com/play", t.lastUrl)
    }
    @Test fun nestedAlbumsAndFavoritesUseDistinctQueries() = runBlocking {
        val result = source(Transport()).collections()
        assertEquals(listOf("folder", "album"), result.collections.filterNot { it.id.startsWith("smart:") }.map { it.id })
        assertEquals("folder", result.collections.first { it.id == "album" }.parentId)
        assertEquals(result.members["album"], result.members[GalleryQuery.FAVORITES])
        assertEquals(1, result.members["album"]!!.size)
    }
    @Test fun resourceIdentityIgnoresMetadataButIncludesPosterBytes() = runBlocking {
        val transport = Transport()
        val catalog = source(transport)
        val initial = catalog.fetchPage(null, 100).records.single().resourceFingerprint
        transport.masterRevision = "metadata-only"
        assertEquals(initial, catalog.fetchPage(null, 100).records.single().resourceFingerprint)
        transport.posterFingerprint = "new-poster-bytes"
        assertNotEquals(initial, catalog.fetchPage(null, 100).records.single().resourceFingerprint)
    }
    @Test fun changePagesHydratePairsAndExposeTombstones() = runBlocking {
        val t = Transport()
        val s = source(t)
        assertEquals("before", s.captureSyncToken())
        val page = s.changes("before", s.fetchPage(null, 100).records)
        assertEquals("after", page.nextToken)
        assertEquals("asset", page.upserts.single().assetRecordName)
        assertEquals(setOf("gone"), page.deletedRecordNames)
        t.expiredToken = true
        assertTrue(runCatching { s.changes("old", emptyList()) }.exceptionOrNull() is CatalogResetRequired)
    }
    private class Transport : AppleHttpTransport {
        var expire = false; var expiredToken = false; var opens = 0; var lookups = 0; var lastUrl = ""
        var masterRevision = "m1"
        var posterFingerprint = "poster-bytes"
        override val sessionHeaders = AppleSessionHeaders()
        private val master get() = """{"recordName":"master","recordType":"CPLMaster","recordChangeTag":"$masterRevision","fields":{"filenameEnc":{"value":"dmlkZW8ubXA0"},"itemType":{"value":"public.mpeg-4"},"resOriginalFingerprint":{"value":"hash"},"resOriginalRes":{"value":{"downloadURL":"https://cws.icloud-content.com/original"}},"resVidMedRes":{"value":{"downloadURL":"https://cws.icloud-content.com/play"}},"resJPEGMedRes":{"value":{"downloadURL":"https://cws.icloud-content.com/poster","fileChecksum":"$posterFingerprint"}}}}"""
        private val asset = """{"recordName":"asset","recordType":"CPLAsset","recordChangeTag":"a1","fields":{"masterRef":{"value":{"recordName":"master"}},"assetDate":{"value":1777777777000},"duration":{"value":3000,"type":"INT64"}}}"""
        override suspend fun execute(request: AppleHttpRequest): AppleHttpResponse {
            val body = request.body.orEmpty()
            val result = when {
                request.url.contains("requestWebAccessState") -> "{}"
                body.contains("CheckIndexingState") -> """{"records":[{"fields":{"state":{"value":"FINISHED"}}}]}"""
                request.url.contains("zones/list") -> """{"zones":[{"zoneID":{"zoneName":"PrimarySync"},"syncToken":"before"}]}"""
                request.url.contains("changes/zone") -> if (expiredToken) """{"errors":[{"serverErrorCode":"SYNC_TOKEN_EXPIRED"}]}""" else
                    """{"zones":[{"syncToken":"after","moreComing":false,"records":[$asset,{"recordName":"gone","deleted":true}]}]}"""
                request.url.contains("records/lookup") -> {
                    lookups++
                    if (body.contains("\"master\"")) """{"records":[$master]}""" else """{"records":[$asset]}"""
                }
                body.contains("CPLAlbumByPositionLive") -> if (body.contains("parentId"))
                    """{"records":[{"recordName":"album","fields":{"albumNameEnc":{"value":"Q29hc3Q="},"albumType":{"value":0}}}]}""" else
                    """{"records":[{"recordName":"folder","fields":{"albumNameEnc":{"value":"VHJpcHM="},"albumType":{"value":3}}}]}"""
                body.contains("CPLContainerRelation") -> """{"records":[{"recordType":"CPLContainerRelation","fields":{"itemId":{"value":"asset"}}}]}"""
                else -> """{"records":[$master,$asset]}"""
            }
            return AppleHttpResponse(200, emptyMap(), result)
        }
        override suspend fun openRange(url: String, position: Long, length: Long): dev.mela.engine.source.MediaRead {
            opens++; lastUrl = url
            if (expire && opens == 1) throw AppleMediaHttpException(403)
            return object : dev.mela.engine.source.MediaRead {
                override val input = ByteArrayInputStream("data".toByteArray()); override val length = 4L
                override fun close() = input.close()
            }
        }
        override suspend fun stream(url: String, output: OutputStream) = Unit
        override fun snapshotCookies() = emptyList<PersistedCookie>()
        override fun restore(snapshot: AppleSessionSnapshot) = Unit
        override fun clear() = Unit
    }
}
