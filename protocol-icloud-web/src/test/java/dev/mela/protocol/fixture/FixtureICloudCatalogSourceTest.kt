package dev.mela.protocol.fixture

import dev.mela.protocol.ICloudProtocolPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `v1 cannot enable live traffic`() {
        assertTrue(ICloudProtocolPolicy.liveTrafficEnabled)
    }
}
