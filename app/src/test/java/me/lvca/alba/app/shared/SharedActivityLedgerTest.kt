package me.lvca.alba.app.shared

import me.lvca.alba.app.sharing.SharedActivityLedger
import me.lvca.alba.app.sharing.SharedAlbumMark
import me.lvca.alba.app.sharing.SharedAlbumNews
import me.lvca.alba.engine.model.GalleryCollection
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.engine.model.SharedAlbumGeneration
import me.lvca.alba.engine.model.SharedAlbumInfo
import me.lvca.alba.engine.model.SharedAlbumRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedActivityLedgerTest {
    private val names = mapOf("family" to "Family", "trip" to "Trip")

    @Test fun marksCountSharedAlbumItemsAndTheirNewestAddition() {
        val shared = SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.VIEWER)
        val albums = listOf(GalleryCollection("family", "Family", shared = shared), GalleryCollection("personal", "Mine"))
        fun media(id: String, added: Long?, vararg albums: String) = GalleryMedia(id, "$id.jpg", 5, 1, 1, MediaOrigin.ICLOUD,
            MediaAvailability.CLOUD_ONLY, null, null, 0, 0, collectionIds = albums.toSet(), addedAtEpochMillis = added)
        val marks = SharedAlbumMark.of(albums, listOf(media("a", 10, "family"), media("b", null, "family"), media("c", 99, "personal")))
        assertEquals(mapOf("family" to SharedAlbumMark(2, 10)), marks)
    }

    @Test fun albumsSeenForTheFirstTimeAreReadAndNewPhotosAreUnreadUntilOpened() {
        val first = mapOf("family" to SharedAlbumMark(3, 100), "trip" to SharedAlbumMark(0, 0))
        var ledger = SharedActivityLedger().observe(first)
        assertTrue(ledger.unread(first).isEmpty())
        // An empty album is not a baseline: the catalog may list it a moment before its photos.
        val withPhotos = mapOf("family" to SharedAlbumMark(4, 200), "trip" to SharedAlbumMark(5, 50))
        ledger = ledger.observe(withPhotos)
        assertEquals(setOf("family"), ledger.unread(withPhotos))
        ledger = ledger.markRead("family", withPhotos.getValue("family"))
        assertTrue(ledger.unread(withPhotos).isEmpty())
    }

    @Test fun removalsAloneAreNotNewsAndAPartialCatalogKeepsWhatWasRead() {
        val seen = mapOf("family" to SharedAlbumMark(3, 100))
        val ledger = SharedActivityLedger().observe(seen).observe(emptyMap())
        assertEquals(seen, ledger.seen)
        assertTrue(ledger.unread(mapOf("family" to SharedAlbumMark(2, 100))).isEmpty())
    }

    @Test fun newsIsAnnouncedOnceAndCountsOnlyWhatIsNew() {
        val before = mapOf("family" to SharedAlbumMark(3, 100))
        var ledger = SharedActivityLedger().observe(before)
        val after = mapOf("family" to SharedAlbumMark(5, 300))
        val news = ledger.news(after, names)
        assertEquals(listOf(SharedAlbumNews("family", "Family", 2)), news)
        ledger = ledger.announce(news, after)
        assertTrue(ledger.news(after, names).isEmpty())
        // Still unread in the app: announcing is not opening.
        assertEquals(setOf("family"), ledger.unread(after))
        val later = mapOf("family" to SharedAlbumMark(6, 400))
        assertEquals(1, ledger.news(later, names).single().newItems)
    }

    @Test fun invitationsBadgeUntilTheInboxIsOpenedAndAreAnnouncedOnce() {
        var ledger = SharedActivityLedger().observeInvitations(setOf("one", "two"))
        assertEquals(setOf("one", "two"), ledger.unreadInvitations)
        assertEquals(setOf("one", "two"), ledger.newInvitations)
        ledger = ledger.announceInvitations(setOf("one", "two"))
        assertTrue(ledger.newInvitations.isEmpty())
        assertEquals(2, ledger.unreadInvitations.size)
        ledger = ledger.markInvitationsRead().observeInvitations(setOf("two", "three"))
        assertEquals(setOf("three"), ledger.unreadInvitations)
        assertEquals(setOf("three"), ledger.newInvitations)
        // Answered invitations are forgotten, so the stored sets stay small.
        assertEquals(setOf("two"), ledger.seenInvitations)
    }
}
