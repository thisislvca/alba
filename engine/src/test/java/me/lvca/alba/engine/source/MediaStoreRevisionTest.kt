package me.lvca.alba.engine.source

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaStoreRevisionTest {
    @Test fun revisionFormatStaysStableForStoredRows() {
        // Stored staged sources and catalog rows compare against this exact text.
        assertEquals("v7:external_primary:42:9:1024", mediaStoreRevision("v7", "external_primary", 42, 9, 1024))
        assertEquals("legacy:external:1:-1:-1", mediaStoreRevision("legacy", "external", 1, -1, -1))
    }
}
