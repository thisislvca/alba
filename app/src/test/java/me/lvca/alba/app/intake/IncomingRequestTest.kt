package me.lvca.alba.app.intake

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingRequestTest {
    private val link = "https://photos.icloud.com/shared/album/fixture"

    @Test fun contentUrisAreAcceptedOnceEach() {
        val photo = content("content://media/external/images/1")
        assertEquals(listOf(photo.value), safeIncomingUris(listOf(photo, photo)))
    }

    @Test fun filePathsAndNetworkUrlsRefuseTheWholeRequest() {
        val photo = content("content://media/external/images/1")
        assertTrue(safeIncomingUris(listOf(photo, IncomingUri("file:///data/private.jpg", "file", null))).isEmpty())
        assertTrue(safeIncomingUris(listOf(IncomingUri("https://example.invalid/a.jpg", "https", "example.invalid"))).isEmpty())
        assertTrue(safeIncomingUris(listOf(IncomingUri("content:///no-authority", "content", ""))).isEmpty())
        assertTrue(safeIncomingUris(emptyList()).isEmpty())
    }

    @Test fun oversizedPayloadsAreRefused() {
        val atLimit = List(MAX_INCOMING_ITEMS) { content("content://media/$it") }
        assertEquals(MAX_INCOMING_ITEMS, safeIncomingUris(atLimit).size)
        assertTrue(safeIncomingUris(atLimit + content("content://media/extra")).isEmpty())
        assertTrue(safeIncomingUris(listOf(content("content://media/" + "x".repeat(32_000)))).isEmpty())
    }

    @Test fun sharedTextAndViewedLinksBecomeInvitations() {
        assertEquals(link, sharedInvitationLink(Intent.ACTION_SEND, "text/plain", "Album\n$link", null, null))
        assertEquals(link, sharedInvitationLink(Intent.ACTION_VIEW, null, null, "https", link))
    }

    @Test fun thirdPartyLinksAreRejectedButStillHandledAsInvitations() {
        assertEquals("", sharedInvitationLink(Intent.ACTION_VIEW, null, null, "https", "https://evil.test/shared/album/fixture"))
        assertEquals("", sharedInvitationLink(Intent.ACTION_SEND, "text/plain", "no link here", null, null))
    }

    @Test fun mediaIntentsAreNotInvitations() {
        assertNull(sharedInvitationLink(Intent.ACTION_VIEW, "image/jpeg", null, "content", "content://gallery/photo"))
        assertNull(sharedInvitationLink(Intent.ACTION_SEND, "image/jpeg", null, null, null))
        assertNull(sharedInvitationLink(Intent.ACTION_MAIN, null, null, null, null))
    }

    private fun content(value: String) = IncomingUri(value, "content", value.removePrefix("content://").substringBefore('/'))

    @Test fun webLinksThatAreNotSharedAlbumsGoBackToTheBrowser() {
        assertEquals("https://www.icloud.com/photos/", browserLink(Intent.ACTION_VIEW, "https", "https://www.icloud.com/photos/"))
        assertNull(browserLink(Intent.ACTION_VIEW, "https", link))
        assertNull(browserLink(Intent.ACTION_SEND, null, null))
    }
}
