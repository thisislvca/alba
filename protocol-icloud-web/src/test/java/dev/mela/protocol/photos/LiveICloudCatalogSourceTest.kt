package dev.mela.protocol.photos

import dev.mela.protocol.account.AppleSessionSnapshot
import dev.mela.protocol.account.PersistedCookie
import dev.mela.protocol.auth.AppleProtocolError
import dev.mela.protocol.auth.AppleProtocolException
import dev.mela.protocol.network.AppleHttpRequest
import dev.mela.protocol.network.AppleHttpResponse
import dev.mela.protocol.network.AppleHttpTransport
import dev.mela.protocol.network.AppleSessionHeaders
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveICloudCatalogSourceTest {
    @Test
    fun `authentication rejection expires the exact catalog session`() = runBlocking {
        var rejected: AppleSessionSnapshot? = null
        val transport = object : AppleHttpTransport {
            override val sessionHeaders = AppleSessionHeaders(
                accountCountryCode = SESSION.accountCountryCode,
                sessionId = SESSION.sessionId,
                sessionToken = SESSION.sessionToken,
                trustToken = SESSION.trustToken,
            )
            override suspend fun execute(request: AppleHttpRequest) =
                AppleHttpResponse(401, emptyMap(), "{}")
            override suspend fun stream(url: String, output: OutputStream) = error("Not used")
            override fun snapshotCookies() = SESSION.cookies
            override fun restore(snapshot: AppleSessionSnapshot) = Unit
            override fun clear() = Unit
        }
        val source = LiveICloudCatalogSource(
            transport = transport,
            sessionProvider = { SESSION },
            onSessionRejected = { rejected = it },
        )

        val error = runCatching { source.captureSyncToken() }.exceptionOrNull()

        assertTrue(error is AppleProtocolException)
        assertEquals(AppleProtocolError.SESSION_EXPIRED, (error as AppleProtocolException).error)
        assertEquals(SESSION, rejected)
    }

    @Test fun scrollingUsesTheSmallThumbnailAndOpeningUsesTheFullJpeg() = runBlocking {
        val page = PHOTO_PAGE.replace("\"resJPEGMedRes\":", """
            "resJPEGThumbRes":{"value":{"downloadURL":"$THUMB_URL"}},
            "resJPEGFullRes":{"value":{"downloadURL":"$FULL_URL"}},
            "resJPEGMedRes":
        """.trimIndent())
        val transport = FakePhotosTransport(firstPage = page)
        val source = LiveICloudCatalogSource(transport, { SESSION })
        val media = source.fetchPage(null, 10).records.single()
        source.writePreview(media.id, ByteArrayOutputStream())
        assertEquals(listOf(THUMB_URL), transport.streamedUrls)
        source.writeDisplay(media.id, ByteArrayOutputStream())
        assertEquals(listOf(THUMB_URL, FULL_URL), transport.streamedUrls)
    }

    @Test fun missingThumbnailNeverSilentlyDownloadsTheOriginalWhileScrolling() = runBlocking {
        val page = PHOTO_PAGE.replace("\"resJPEGMedRes\"", "\"unavailableDerivative\"")
        val transport = FakePhotosTransport(firstPage = page)
        val source = LiveICloudCatalogSource(transport, { SESSION })
        val media = source.fetchPage(null, 10).records.single()
        assertTrue(runCatching { source.writePreview(media.id, ByteArrayOutputStream()) }.isFailure)
        assertTrue(transport.streamedUrls.isEmpty())
        source.writeDisplay(media.id, ByteArrayOutputStream())
        assertEquals(listOf(ORIGINAL_URL), transport.streamedUrls)
    }

    @Test fun recentlyDeletedIsSeparateFromTheLibraryAndHandlesClearedRestoreFlags() = runBlocking {
        val trashedPage = PHOTO_PAGE.replace("\"assetDate\":", "\"isDeleted\":{\"value\":1},\"assetDate\":")
        val parser = CloudKitPhotoResponseParser()
        assertTrue(parser.parse(trashedPage).photos.isEmpty())
        assertTrue(parser.parse(trashedPage, includeTrashed = true).photos.single().isTrashed)
        val restored = trashedPage.replace("\"isDeleted\":{\"value\":1}", "\"isDeleted\":null")
        assertFalse(parser.parse(restored).photos.single().isTrashed)
        val purged = trashedPage.replace("\"assetDate\":", "\"isExpunged\":{\"value\":1},\"assetDate\":")
        assertTrue(parser.parse(purged, includeTrashed = true).photos.isEmpty())
        val transport = FakePhotosTransport(firstPage = trashedPage)
        val source = LiveICloudCatalogSource(transport, { SESSION }, isAuthorized = { true })
        assertTrue(source.recentlyDeleted().single().isTrashed)
        assertTrue(transport.photoQueries.single().contains("CPLAssetAndMasterDeletedByExpungedDate"))
    }

    @Test fun incompleteRecentlyDeletedDoesNotSilentlyEraseTheCachedTrash() = runBlocking {
        val transport = FakePhotosTransport(firstPage = PHOTO_PAGE.replace("\"master-1\",", "\"missing-master\","))
        val source = LiveICloudCatalogSource(transport, { SESSION }, isAuthorized = { true })
        assertTrue(runCatching { source.recentlyDeleted() }.isFailure)
    }
    @Test
    fun `pages Personal Library and streams signed preview and original URLs`() = runBlocking {
        val transport = FakePhotosTransport()
        val savedSessions = mutableListOf<AppleSessionSnapshot>()
        val source = LiveICloudCatalogSource(
            transport = transport,
            sessionProvider = { SESSION },
            onSessionUpdated = savedSessions::add,
        )

        val first = source.fetchPage(cursor = null, limit = 1)

        assertEquals(1, first.records.size)
        val media = first.records.single()
        assertTrue(media.id.startsWith("icloud:"))
        assertFalse(media.id.contains(SESSION.accountName))
        assertEquals("IMG_0001.JPG", media.fileName)
        assertEquals(4_032, media.width)
        assertEquals(3_024, media.height)
        assertEquals(1_777_777_777_000L, media.capturedAtEpochMillis)
        assertEquals("sync-token-1", first.changeToken)
        assertNotNull(first.nextCursor)

        val preview = ByteArrayOutputStream()
        source.writePreview(media.id, preview)
        val original = ByteArrayOutputStream()
        source.writeOriginal(media.id, original)

        assertEquals("preview", preview.toString(Charsets.UTF_8.name()))
        assertEquals("original", original.toString(Charsets.UTF_8.name()))
        assertEquals(
            listOf(PREVIEW_URL, ORIGINAL_URL),
            transport.streamedUrls,
        )

        val second = source.fetchPage(first.nextCursor, limit = 1)
        assertTrue(second.records.isEmpty())
        assertNull(second.nextCursor)
        assertEquals(2, transport.photoQueries.size)
        assertTrue(transport.photoQueries.first().contains("\"resultsLimit\":2"))
        assertTrue(transport.photoQueries.first().contains("\"value\":0"))
        assertTrue(transport.photoQueries.last().contains("\"value\":1"))
        assertTrue(savedSessions.isNotEmpty())
    }

    @Test
    fun `stops before CloudKit when protected web access is disabled`() = runBlocking {
        val transport = FakePhotosTransport(webAccessDisabled = true)
        val source = LiveICloudCatalogSource(transport, sessionProvider = { SESSION })

        val error = runCatching { source.fetchPage(null, 10) }.exceptionOrNull()

        assertTrue(error is AppleProtocolException)
        assertEquals(AppleProtocolError.PHOTOS_UNAVAILABLE, (error as AppleProtocolException).error)
        assertTrue(transport.photoQueries.isEmpty())
    }

    @Test
    fun `rank paging advances past filtered videos instead of truncating the library`() = runBlocking {
        val transport = FakePhotosTransport(firstPage = VIDEO_AND_PHOTO_PAGE)
        val source = LiveICloudCatalogSource(transport, sessionProvider = { SESSION })

        val first = source.fetchPage(null, limit = 2)
        assertEquals(1, first.records.size)
        assertNotNull(first.nextCursor)

        source.fetchPage(first.nextCursor, limit = 2)
        assertTrue(transport.photoQueries.last().contains("\"value\":2"))
    }

    @Test
    fun `continuation keeps its original start rank`() = runBlocking {
        val transport = FakePhotosTransport(firstPage = CONTINUED_PHOTO_PAGE)
        val source = LiveICloudCatalogSource(transport, sessionProvider = { SESSION })

        val first = source.fetchPage(null, limit = 2)
        val second = source.fetchPage(first.nextCursor, limit = 2)

        assertNull(second.nextCursor)
        assertTrue(transport.photoQueries.last().contains("\"continuationMarker\":\"next-fragment\""))
        assertTrue(transport.photoQueries.last().contains("\"value\":0"))
    }

    @Test
    fun `completed refresh releases stale signed download references`() = runBlocking {
        val transport = FakePhotosTransport()
        val source = LiveICloudCatalogSource(transport, sessionProvider = { SESSION })
        val first = source.fetchPage(null, limit = 1)
        val oldMediaId = first.records.single().id
        source.fetchPage(first.nextCursor, limit = 1)

        val emptyRefresh = source.fetchPage(null, limit = 1)
        assertTrue(emptyRefresh.records.isEmpty())
        val error = runCatching {
            source.writePreview(oldMediaId, ByteArrayOutputStream())
        }.exceptionOrNull()

        assertTrue(error is AppleProtocolException)
        assertEquals(AppleProtocolError.PHOTOS_UNAVAILABLE, (error as AppleProtocolException).error)
        assertTrue(transport.streamedUrls.isEmpty())
    }

    private class FakePhotosTransport(
        private val webAccessDisabled: Boolean = false,
        private val firstPage: String = PHOTO_PAGE,
    ) : AppleHttpTransport {
        val photoQueries = mutableListOf<String>()
        val streamedUrls = mutableListOf<String>()
        private var page = 0

        override val sessionHeaders = AppleSessionHeaders(
            accountCountryCode = SESSION.accountCountryCode,
            sessionId = SESSION.sessionId,
            sessionToken = SESSION.sessionToken,
            trustToken = SESSION.trustToken,
        )

        override suspend fun execute(request: AppleHttpRequest): AppleHttpResponse {
            return when {
                request.url.contains("requestWebAccessState") -> response(
                    "{\"isICDRSDisabled\":$webAccessDisabled}",
                )

                request.body.orEmpty().contains("CheckIndexingState") -> response(
                    """
                    {"records":[{"fields":{"state":{"value":"FINISHED"}}}]}
                    """.trimIndent(),
                )

                request.url.contains("/records/query") -> {
                    photoQueries += request.body.orEmpty()
                    response(if (page++ == 0) firstPage else EMPTY_PAGE)
                }

                request.url.contains("/records/lookup") -> response("""{"records":[{"recordName":"asset-1","serverErrorCode":"NOT_FOUND"}]}""")
                else -> error("Unexpected request: ${request.method} ${request.url}")
            }
        }

        override suspend fun stream(url: String, output: OutputStream) {
            streamedUrls += url
            output.write(
                when (url) {
                    PREVIEW_URL -> "preview"
                    ORIGINAL_URL -> "original"
                    THUMB_URL -> "thumbnail"
                    FULL_URL -> "full jpeg"
                    else -> error("Unexpected stream URL")
                }.toByteArray(),
            )
        }

        override fun snapshotCookies(): List<PersistedCookie> = SESSION.cookies

        override fun restore(snapshot: AppleSessionSnapshot) = Unit

        override fun clear() = Unit

        private fun response(body: String) = AppleHttpResponse(200, emptyMap(), body)
    }

    private companion object {
        const val PREVIEW_URL = "https://p01-content.icloud.com/download/preview"
        const val ORIGINAL_URL = "https://p01-content.icloud.com/download/original"
        const val THUMB_URL = "https://p01-content.icloud.com/download/thumb"
        const val FULL_URL = "https://p01-content.icloud.com/download/full"
        val SESSION = AppleSessionSnapshot(
            accountName = "person@example.com",
            clientId = "client-id",
            accountCountryCode = "IT",
            sessionId = "session-id",
            sessionToken = "session-token",
            trustToken = "trust-token",
            dsid = "123456789",
            webservices = mapOf("ckdatabasews" to "https://p01-ckdatabasews.icloud.com"),
            cookies = emptyList(),
        )
        val PHOTO_PAGE =
            """
            {
              "syncToken":"sync-token-1",
              "records":[
                {
                  "recordName":"master-1",
                  "recordType":"CPLMaster",
                  "recordChangeTag":"master-tag",
                  "fields":{
                    "filenameEnc":{"value":"SU1HXzAwMDEuSlBH"},
                    "itemType":{"value":"public.jpeg"},
                    "resOriginalWidth":{"value":4032},
                    "resOriginalHeight":{"value":3024},
                    "resJPEGMedRes":{"value":{"downloadURL":"$PREVIEW_URL","size":1234}},
                    "resOriginalRes":{"value":{"downloadURL":"$ORIGINAL_URL","size":5678}}
                  }
                },
                {
                  "recordName":"asset-1",
                  "recordType":"CPLAsset",
                  "recordChangeTag":"asset-tag",
                  "fields":{
                    "masterRef":{"value":{"recordName":"master-1"}},
                    "assetDate":{"value":1777777777000}
                  }
                }
              ]
            }
            """.trimIndent()
        val VIDEO_AND_PHOTO_PAGE =
            """
            {
              "syncToken":"sync-token-video",
              "records":[
                {
                  "recordName":"video-master",
                  "recordType":"CPLMaster",
                  "recordChangeTag":"video-master-tag",
                  "fields":{
                    "filenameEnc":{"value":"VklEXzAwMDEuTU9W"},
                    "itemType":{"value":"com.apple.quicktime-movie"},
                    "resOriginalRes":{"value":{"downloadURL":"$ORIGINAL_URL"}}
                  }
                },
                {
                  "recordName":"video-asset",
                  "recordType":"CPLAsset",
                  "recordChangeTag":"video-asset-tag",
                  "fields":{"masterRef":{"value":{"recordName":"video-master"}}}
                },
                ${PHOTO_PAGE.substringAfter("\"records\":[").substringBeforeLast("]")}
              ]
            }
            """.trimIndent()
        val CONTINUED_PHOTO_PAGE = PHOTO_PAGE.replace(
            "\"syncToken\":\"sync-token-1\"",
            "\"syncToken\":\"sync-token-1\",\"continuationMarker\":\"next-fragment\"",
        )
        const val EMPTY_PAGE = "{\"syncToken\":\"sync-token-2\",\"records\":[]}"
    }
}
