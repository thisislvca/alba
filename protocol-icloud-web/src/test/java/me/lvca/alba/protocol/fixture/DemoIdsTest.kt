package me.lvca.alba.protocol.fixture

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoIdsTest {
    @Test fun demoMediaAndSharedAlbumsAreDemoIds() {
        assertTrue(isDemoId("fixture:icloud:0001"))
        assertTrue(isDemoId("shared:demo-library:private:owner:two:video"))
        assertTrue(isDemoSharedId("shared:demo-library:legacy:owner:one"))
        assertFalse(isDemoSharedId("fixture:icloud:0001"))
        assertFalse(isDemoId("icloud:ABC"))
        assertFalse(isDemoId("shared:live-account:private:owner:album"))
        assertFalse(isDemoId("device:external:1"))
    }

    @Test fun everyIdTheDemoCatalogPublishesIsADemoId() = runBlocking {
        val catalog = FixtureICloudCatalogSource(includeSharedAlbums = true)
        val page = catalog.fetchPage(null, 100)
        assertTrue(page.records.isNotEmpty())
        page.records.forEach { assertTrue(it.id, isDemoId(it.id)) }
        val shared = catalog.sharedAlbums()
        assertTrue(shared.collections.collections.isNotEmpty())
        shared.collections.collections.forEach { assertTrue(it.id, isDemoSharedId(it.id)) }
        shared.records.forEach { assertTrue(it.id, isDemoSharedId(it.id)) }
    }
}
