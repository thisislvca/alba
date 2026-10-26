package me.lvca.alba.protocol.fixture

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureICloudCatalogSourceTest {
    @Test
    fun `catalog paging is stable and complete`() = runBlocking {
        val source = FixtureICloudCatalogSource()
        val first = source.fetchPage(cursor = null, limit = 8)
        val second = source.fetchPage(cursor = first.nextCursor, limit = 8)
        val third = source.fetchPage(cursor = second.nextCursor, limit = 8)
        val ids = (first.records + second.records + third.records).map { it.id }

        // Twenty sample photos and two more frames of the sample burst; the Hidden sample is not in the library feed.
        assertEquals(22, ids.size)
        assertEquals(ids.size, ids.distinct().size)
        assertNull(third.nextCursor)
        assertTrue(ids.all { it.startsWith("fixture:icloud:") })
    }

    @Test
    fun `demo has a hidden photo, a burst, captions and places, and hiding round trips`() = runBlocking {
        val source = FixtureICloudCatalogSource()
        val library = source.fetchPage(null, 100).records
        val hidden = source.hiddenMedia()
        assertEquals(listOf("fixture:icloud:0023"), hidden.map { it.id })
        assertTrue(hidden.single().isHidden)
        assertTrue(library.none { it.isHidden || it.id == "fixture:icloud:0023" })
        assertEquals(3, library.count { it.details.burstId != null })
        assertTrue(library.any { it.details.caption != null && it.details.hasLocation && it.details.timeZoneOffsetSeconds != null })
        val photo = library.first { it.details.burstId == null }.id
        source.setHidden(listOf(photo), true)
        assertTrue(source.fetchPage(null, 100).records.none { it.id == photo })
        assertTrue(source.hiddenMedia().any { it.id == photo && it.isHidden })
        assertTrue(source.changes(source.captureSyncToken(), emptyList()).upserts.single { it.id == photo }.isHidden)
        source.setHidden(listOf(photo), false)
        assertTrue(source.fetchPage(null, 100).records.any { it.id == photo && !it.isHidden })
    }

    @Test
    fun `populated shared demo preserves media identities and album isolation`() = runBlocking {
        val source = FixtureICloudCatalogSource(includeSharedAlbums = true)
        val snapshot = source.sharedAlbums()
        val records = snapshot.records.associateBy { it.id }
        assertEquals(8, snapshot.collections.collections.size)
        snapshot.collections.members.forEach { (albumId, ids) ->
            assertTrue(ids.size >= 4)
            assertTrue(ids.all { it.startsWith("$albumId:") && records.containsKey(it) })
            ids.forEach { source.sharedDiscussion(it) }
        }
        assertTrue(records.containsKey("shared:demo-library:legacy:owner:one:photo"))
        assertTrue(records.containsKey("shared:demo-library:private:owner:two:video"))
        assertEquals(records.keys, snapshot.collections.members.values.flatten().toSet())
        assertTrue(FixtureICloudCatalogSource().sharedAlbums().records.isEmpty())
    }
}
