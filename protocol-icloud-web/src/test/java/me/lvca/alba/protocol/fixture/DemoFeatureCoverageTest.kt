package me.lvca.alba.protocol.fixture

import kotlinx.coroutines.runBlocking
import me.lvca.alba.engine.model.*
import me.lvca.alba.engine.source.CloudCatalogSource
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Contract checks against the shipped demo backend, not a test-only substitute. */
class DemoFeatureCoverageTest {
    private val photo = "fixture:icloud:0001"
    private val owner = "shared:demo-library:private:owner:two"

    @Test fun signedOutCatalogNeverFallsBackToFixtures() = runBlocking {
        val sample = java.lang.reflect.Proxy.newProxyInstance(CloudCatalogSource::class.java.classLoader,
            arrayOf(CloudCatalogSource::class.java)) { _, method, _ ->
            throw AssertionError("Signed-out mode reached cloud source: ${method.name}")
        } as CloudCatalogSource
        val source = me.lvca.alba.protocol.photos.AccountAwareICloudCatalogSource(sample, sample) { null }
        assertTrue(source.fetchPage(null, 100).records.isEmpty())
        assertTrue(source.collections().collections.isEmpty())
        assertTrue(source.sharedAlbums().records.isEmpty())
        assertNull(source.accountProfile())
        assertNull(source.writeAccountId)
        assertTrue(runCatching { source.writeOriginal("fixture:1", java.io.ByteArrayOutputStream()) }.isFailure)
    }

    @Test fun signedOutCatalogRoutesSampleReadsAndWritesAwayFromTheLiveBackend() = runBlocking {
        val type = me.lvca.alba.engine.source.CloudCatalogSource::class.java
        val live = java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            throw AssertionError("Demo reached live backend: ${method.name}")
        } as me.lvca.alba.engine.source.CloudCatalogSource
        val sample = FixtureICloudCatalogSource(includeSharedAlbums = true, openVideo = { byteArrayOf(1, 2, 3).inputStream() })
        val source = me.lvca.alba.protocol.photos.AccountAwareICloudCatalogSource(sample, live, { true }) { null }
        assertEquals("demo-library", source.accountLabel)
        assertEquals(20, source.fetchPage(null, 100).records.size)
        source.setFavorite(photo, true)
        source.createAlbum("Local demo album")
        source.changeSharedAlbum(owner, SharedCommand(SharedAction.INVITE, value = "sample@example.invalid"), "sample-invite")
        assertEquals(8, source.sharedAlbums().collections.collections.size)
        assertTrue(source.storageUsage().isDemo)
        source.openPlayback("fixture:icloud:0019", 0, -1).use { assertEquals(3, it.input.readBytes().size) }
        source.openPlayback("$owner:video", 0, -1).use { assertEquals(3, it.input.readBytes().size) }
    }

    @Test fun personalLibraryMutationsSurviveRefreshWithinTheDemoSession() = runBlocking {
        val source = FixtureICloudCatalogSource()
        val album = source.createAlbum("Sample trip")
        source.renameAlbum(album.id, "Weekend")
        source.addToAlbum(album.id, listOf(photo))
        source.setFavorite(photo, true)
        val snapshot = source.collections()
        assertEquals("Weekend", snapshot.collections.single { it.id == album.id }.name)
        assertTrue(photo in snapshot.members.getValue(album.id))
        assertTrue(photo in snapshot.members.getValue(GalleryQuery.FAVORITES))
        source.setCloudTrashed(listOf(photo), true)
        assertTrue(source.recentlyDeleted().any { it.id == photo && it.isTrashed })
        assertFalse(source.fetchPage(null, 100).records.any { it.id == photo })
        assertTrue(photo in source.changes(source.captureSyncToken(), emptyList()).deletedRecordNames)
        source.setCloudTrashed(listOf(photo), false)
        assertTrue(source.changes(source.captureSyncToken(), emptyList()).upserts.any { it.id == photo })
        source.setFavorite(photo, false)
        source.removeFromAlbum(album.id, listOf(photo))
        assertFalse(photo in source.collections().members.getValue(album.id))
        assertFalse(photo in source.collections().members.getValue(GalleryQuery.FAVORITES))
        source.addToAlbum(album.id, listOf(photo))
        val library = source.fetchPage(null, 100).records.map { it.id }
        source.deleteAlbum(album.id)
        assertFalse(source.collections().collections.any { it.id == album.id })
        // Deleting an album keeps its photos in the library.
        assertEquals(library, source.fetchPage(null, 100).records.map { it.id })
    }

    @Test fun bothSharedAlbumGenerationsSupportCreateContributeRemoveAndDelete() = runBlocking {
        val source = FixtureICloudCatalogSource(includeSharedAlbums = true)
        for (generation in SharedAlbumGeneration.entries) {
            val album = source.createSharedAlbum("Sample", generation.name, generation)
            assertEquals(album, source.createSharedAlbum("Sample", generation.name, generation))
            source.contributeToSharedAlbum(album.id, listOf(photo), "contribute")
            val sharedPhoto = source.sharedAlbums().collections.members.getValue(album.id).single()
            assertTrue(source.sharedAlbums().records.any { it.id == sharedPhoto })
            if (generation == SharedAlbumGeneration.MODERN) {
                val post = source.sharedActivity(album.id, 0).posts.single()
                assertTrue(sharedPhoto in source.sharedPostPhotos(post.id))
            }
            source.changeSharedAlbum(album.id, SharedCommand(SharedAction.RENAME, value = "Renamed"), "rename-${generation.name}")
            assertEquals("Renamed", source.sharedManagement(album.id).album.name)
            source.changeSharedAlbum(album.id, SharedCommand(SharedAction.REMOVE_MEDIA, sharedPhoto), "remove-${generation.name}")
            assertTrue(source.sharedAlbums().collections.members.getValue(album.id).isEmpty())
            source.changeSharedAlbum(album.id, SharedCommand(SharedAction.DELETE_ALBUM), "delete-${generation.name}")
            assertFalse(source.sharedAlbums().collections.collections.any { it.id == album.id })
        }
    }

    @Test fun participantAndSettingsMocksChangeObservableStateWithoutSendingInvitations() = runBlocking {
        val source = FixtureICloudCatalogSource(includeSharedAlbums = true)
        source.changeSharedAlbum(owner, SharedCommand(SharedAction.INVITE, value = "guest@example.invalid"), "invite")
        val guest = source.sharedManagement(owner).participants.single { it.id == "invite" }
        assertTrue(guest.pending)
        source.changeSharedAlbum(owner, SharedCommand(SharedAction.ROLE, guest.id, "manager"), "role")
        assertEquals(SharedAlbumRole.MANAGER, source.sharedManagement(owner).participants.single { it.id == guest.id }.role)
        for (action in listOf(SharedAction.PUBLIC_ACCESS, SharedAction.CONTRIBUTIONS, SharedAction.ACCESS_REQUESTS, SharedAction.TEMPORARY)) {
            source.changeSharedAlbum(owner, SharedCommand(action, enabled = true), action.name)
        }
        with(source.sharedManagement(owner)) {
            assertTrue(publicAccess && allowContributions && allowAccessRequests && temporary)
        }
        source.changeSharedAlbum(owner, SharedCommand(SharedAction.CREATE_INVITE_LINK), "link")
        assertTrue(source.sharedManagement(owner).invitationLinks.single().startsWith("https://example.invalid/"))
        source.changeSharedAlbum(owner, SharedCommand(SharedAction.REVOKE_INVITE_LINKS), "revoke")
        assertTrue(source.sharedManagement(owner).invitationLinks.isEmpty())
        source.changeSharedAlbum(owner, SharedCommand(SharedAction.REMOVE_PARTICIPANT, guest.id), "remove")
        assertFalse(source.sharedManagement(owner).participants.any { it.id == guest.id })
    }

    @Test fun photoAndPostDiscussionsSupportCommentsReactionsAndDeletion() = runBlocking {
        val source = FixtureICloudCatalogSource(includeSharedAlbums = true)
        val media = source.sharedAlbums().collections.members.getValue(owner).first()
        val post = source.sharedActivity(owner, 0).posts.single().id
        assertTrue(source.sharedPostPhotos(post).isNotEmpty())
        assertTrue(source.sharedActivity(owner, 1).posts.isEmpty())
        for ((subject, isPost) in listOf(media to false, post to true)) {
            val comment = if (isPost) SharedAction.POST_COMMENT else SharedAction.COMMENT
            val reaction = if (isPost) SharedAction.POST_REACTION else SharedAction.REACTION
            val deletion = if (isPost) SharedAction.POST_DELETE_COMMENT else SharedAction.DELETE_COMMENT
            suspend fun discussion() = if (isPost) source.sharedPostDiscussion(subject) else source.sharedDiscussion(subject)
            val operation = "comment-$isPost"
            val command = SharedCommand(comment, subject, "Sample comment")
            source.changeSharedAlbum(owner, command, operation)
            source.changeSharedAlbum(owner, command, operation)
            assertEquals(1, discussion().comments.count { it.id == operation })
            source.changeSharedAlbum(owner, SharedCommand(reaction, subject, "like"), "like-$isPost")
            assertTrue(discussion().comments.any { it.reaction })
            source.changeSharedAlbum(owner, SharedCommand(reaction, subject, ""), "unlike-$isPost")
            assertFalse(discussion().comments.any { it.reaction })
            source.changeSharedAlbum(owner, SharedCommand(deletion, subject, operation), "delete-comment-$isPost")
            assertTrue(discussion().comments.isEmpty())
        }
    }

    @Test fun viewerAlbumCannotBeRenamedButCanBeLeft() = runBlocking {
        val source = FixtureICloudCatalogSource(includeSharedAlbums = true)
        val id = "shared:demo-library:legacy:owner:one"
        assertFalse(source.sharedManagement(id).album.shared!!.canManage)
        val error = runCatching { source.changeSharedAlbum(id, SharedCommand(SharedAction.RENAME, value = "Invalid"), "rename") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        source.changeSharedAlbum(id, SharedCommand(SharedAction.LEAVE), "leave")
        assertFalse(source.sharedAlbums().collections.collections.any { it.id == id })
        assertFalse(source.sharedAlbums().records.any { it.id.startsWith("$id:") })
    }

    @Test fun invitationsAndApprovalRequestsHaveWorkingOutcomes() = runBlocking {
        val source = FixtureICloudCatalogSource(includeSharedAlbums = true)
        val invitations = source.pendingSharedInvitations()
        assertEquals(2, invitations.size)
        val first = invitations.first()
        assertEquals(first, source.resolveSharedInvitation("https://example.invalid/alba-demo/invitation/${first.id}"))
        source.respondSharedInvitation(first.id, true, "join")
        source.respondSharedInvitation(first.id, true, "join")
        assertEquals(1, source.sharedAlbums().collections.collections.count { it.name == first.name })
        assertEquals(3, source.sharedAlbums().records.count { it.id.startsWith("shared:demo-library:invited:${first.id}:") })
        source.respondSharedInvitation(invitations.last().id, false, "decline")
        assertTrue(source.pendingSharedInvitations().isEmpty())
        val requests = source.sharedManagement(owner).requests
        source.changeSharedAlbum(owner, SharedCommand(SharedAction.APPROVE_REQUEST, requests.first().id), "approve")
        assertTrue(source.sharedManagement(owner).participants.any { it.id == requests.first().id && !it.pending })
        source.changeSharedAlbum(owner, SharedCommand(SharedAction.DENY_REQUEST, requests.last().id), "deny")
        assertTrue(source.sharedManagement(owner).requests.isEmpty())
        assertTrue(source.sharedManagement(owner).blocked.any { it.id == requests.last().id })
        source.changeSharedAlbum(owner, SharedCommand(SharedAction.UNBLOCK, requests.last().id), "unblock")
        assertFalse(source.sharedManagement(owner).blocked.any { it.id == requests.last().id })
    }

    @Test fun selectedCoverAndSavedPersonalCopySurviveCatalogRefresh() = runBlocking {
        val source = FixtureICloudCatalogSource(includeSharedAlbums = true)
        val selected = source.sharedAlbums().collections.members.getValue(owner).last()
        source.changeSharedAlbum(owner, SharedCommand(SharedAction.COVER, selected), "cover")
        assertEquals(selected, source.sharedAlbums().collections.collections.first { it.id == owner }.coverMediaId)
        assertTrue(runCatching { source.changeSharedAlbum(owner, SharedCommand(SharedAction.COVER, photo), "bad-cover") }.isFailure)
        source.changeSharedAlbum(owner, SharedCommand(SharedAction.SAVE_TO_LIBRARY, "$owner:video"), "save-video")
        assertTrue("fixture:saved:save-video" in source.collections().members.getValue(SmartCollection.VIDEOS.id))
        source.changeSharedAlbum(owner, SharedCommand(SharedAction.SAVE_TO_LIBRARY, selected), "save")
        source.changeSharedAlbum(owner, SharedCommand(SharedAction.SAVE_TO_LIBRARY, selected), "save")
        val saved = source.fetchPage(null, 100).records.single { it.id == "fixture:saved:save" }
        assertEquals(source.sharedAlbums().records.first { it.id == selected }.fileName, saved.fileName)
        source.setFavorite(saved.id, true)
        source.setCloudTrashed(listOf(saved.id), true)
        assertTrue(source.recentlyDeleted().any { it.id == saved.id })
        source.setCloudTrashed(listOf(saved.id), false)
        assertTrue(source.changes(source.captureSyncToken(), emptyList()).upserts.any { it.id == saved.id })
    }

    @Test fun mediaReadsUseBundledVideoAndStorageIsExplicitlySampleData() = runBlocking {
        val bytes = ByteArray(64) { it.toByte() }
        val source = FixtureICloudCatalogSource(openVideo = { bytes.inputStream() })
        source.openPlayback("fixture:icloud:0019", 8, 12).use {
            assertEquals(12L, it.length)
            assertArrayEquals(bytes.copyOfRange(8, 20), it.input.readBytes())
        }
        val motion = ByteArrayOutputStream()
        source.writeMotion("fixture:icloud:0020", motion)
        assertArrayEquals(bytes, motion.toByteArray())
        val original = ByteArrayOutputStream()
        source.writeOriginal("fixture:icloud:0019", original)
        assertArrayEquals(bytes, original.toByteArray())
        assertTrue(source.storageUsage().isDemo)
        assertNull(source.writeAccountId)
    }
}
