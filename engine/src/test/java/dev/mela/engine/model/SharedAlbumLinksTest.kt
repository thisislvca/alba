package dev.mela.engine.model

import org.junit.Assert.*
import org.junit.Test

class SharedAlbumLinksTest {
    @Test fun knownFirstPartyRoutesAndSharedText() {
        for (url in listOf("https://photos.icloud.com/shared/album/abc_X-123", "https://www.icloud.com/shared/album/?guid=abc_X-123", "https://www.icloud.com/photos/#/sc,abc_X-123/", "https://www.icloud.com/photos/#/sharedalbums/sc,abc_X-123/")) {
            assertEquals("abc_X-123", SharedAlbumLinks.token(url))
            assertEquals(url, SharedAlbumLinks.extract("Family album\n$url"))
        }
    }
    @Test fun rejectsAmbiguousAndUntrustedInputs() {
        for (url in listOf("http://photos.icloud.com/shared/album/x", "https://photos.icloud.com.evil.test/shared/album/x", "https://user@photos.icloud.com/shared/album/x", "https://photos.icloud.com:444/shared/album/x", "https://photos.icloud.com/shared/album/x?extra=yes", "https://photos.icloud.com/shared/album/x/extra", "https://photos.icloud.com/shared/album/?guid=x&guid=y", "https://www.icloud.com/photos/#/sa,legacy/")) assertNull(SharedAlbumLinks.token(url))
        assertNull(SharedAlbumLinks.extract("https://photos.icloud.com/shared/album/x https://photos.icloud.com/shared/album/y"))
        assertNull(SharedAlbumLinks.extract("x".repeat(9000)))
    }
}
