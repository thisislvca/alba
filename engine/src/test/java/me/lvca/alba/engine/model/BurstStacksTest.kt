package me.lvca.alba.engine.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BurstStacksTest {
    @Test fun eachBurstShowsOnlyItsKeyPhotoInPlace() {
        val items = listOf(photo("a"), photo("b", "burst"), photo("c", "burst"), photo("d"), photo("e", "burst"), photo("f", "single"))
        val stacks = BurstStacks.stack(items)
        assertEquals(listOf("a", "b", "d", "f"), stacks.items.map { it.id })
        assertEquals(mapOf("burst" to 3), stacks.sizes)
    }

    @Test fun iCloudsIndexedBurstPhotoIsTheKeyPhoto() {
        val items = listOf(photo("b", "burst"), photo("c", "burst", indexed = true), photo("d", "burst"))
        assertEquals(listOf("c"), BurstStacks.stack(items).items.map { it.id })
    }

    @Test fun anExpandedBurstShowsEveryPhoto() {
        val items = listOf(photo("b", "burst"), photo("c", "burst"), photo("x", "other"), photo("y", "other"))
        val stacks = BurstStacks.stack(items, expanded = setOf("burst"))
        assertEquals(listOf("b", "c", "x"), stacks.items.map { it.id })
        assertEquals(mapOf("burst" to 2, "other" to 2), stacks.sizes)
    }

    @Test fun aLibraryWithoutBurstsIsReturnedAsIs() {
        val items = listOf(photo("a"), photo("b"))
        assertSame(items, BurstStacks.stack(items).items)
    }

    private fun photo(id: String, burst: String? = null, indexed: Boolean = false) = GalleryMedia(id, "$id.jpg", 1, 10, 10,
        MediaOrigin.ICLOUD, MediaAvailability.CLOUD_ONLY, null, null, 0, 0, details = MediaDetails(burstId = burst),
        collectionIds = if (indexed) setOf(SmartCollection.BURSTS.id) else emptySet())
}
