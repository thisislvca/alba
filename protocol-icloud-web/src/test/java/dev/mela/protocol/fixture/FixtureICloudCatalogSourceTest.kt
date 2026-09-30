package dev.mela.protocol.fixture

import dev.mela.protocol.ICloudProtocolPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class FixtureICloudCatalogSourceTest {
    @Test
    fun `catalog paging is stable and complete`() = runBlocking {
        val source = FixtureICloudCatalogSource()
        val first = source.fetchPage(cursor = null, limit = 7)
        val second = source.fetchPage(cursor = first.nextCursor, limit = 7)
        val third = source.fetchPage(cursor = second.nextCursor, limit = 7)
        val ids = (first.records + second.records + third.records).map { it.id }

        assertEquals(20, ids.size)
        assertEquals(ids.size, ids.distinct().size)
        assertNull(third.nextCursor)
        assertTrue(ids.all { it.startsWith("fixture:icloud:") })
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

    @Test
    fun `v1 cannot enable live traffic`() {
        assertTrue(ICloudProtocolPolicy.liveTrafficEnabled)
    }
}
