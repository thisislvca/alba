package me.lvca.alba.engine.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BurstStacksTest {
    @Test fun eachBurstShowsOnePhotoInPlaceCarryingItsSize() {
        val items = listOf(photo("a"), photo("b", "burst"), photo("c", "burst"), photo("d"), photo("e", "burst"), photo("f", "single"))
        val index = BurstIndex.of(items)
        assertEquals(mapOf("burst" to listOf("b", "c", "e")), index.members)
        val stacked = BurstStacks.stack(items, index)
        assertEquals(listOf("a", "b", "d", "f"), stacked.map { it.id })
        assertEquals(listOf(0, 3, 0, 0), stacked.map { it.burstCount })
    }

    @Test fun iCloudsIndexedBurstPhotoIsTheKeyPhoto() {
        val items = listOf(photo("b", "burst"), photo("c", "burst", indexed = true), photo("d", "burst"))
        assertEquals(listOf("c"), BurstStacks.stack(items, BurstIndex.of(items)).map { it.id })
    }

    @Test fun keysAndSizesComeFromTheWholeLibrary() {
        val library = listOf(photo("b", "burst"), photo("c", "burst", indexed = true), photo("d", "burst"))
        val index = BurstIndex.of(library)
        // A search that matched only two frames, without the key photo, still shows one photo for the whole burst.
        val found = BurstStacks.stack(listOf(library[0], library[2]), index)
        assertEquals(listOf("b"), found.map { it.id })
        assertEquals(3, found.single().burstCount)
        // Photos in the trash are not part of the burst.
        assertEquals(2, BurstIndex.of(library.map { if (it.id == "d") it.copy(isTrashed = true) else it }).size("burst"))
    }

    @Test fun anExpandedBurstShowsEveryPhotoWithoutABadge() {
        val items = listOf(photo("b", "burst"), photo("c", "burst"), photo("x", "other"), photo("y", "other"))
        val stacked = BurstStacks.stack(items, BurstIndex.of(items), expanded = setOf("burst"))
        assertEquals(listOf("b", "c", "x"), stacked.map { it.id })
        assertEquals(listOf(0, 0, 2), stacked.map { it.burstCount })
    }

    @Test fun aLibraryWithoutBurstsIsReturnedAsIs() {
        val items = listOf(photo("a"), photo("b"))
        assertSame(items, BurstStacks.stack(items, BurstIndex.of(items)))
    }

    private fun photo(id: String, burst: String? = null, indexed: Boolean = false) = GalleryMedia(id, "$id.jpg", 1, 10, 10,
        MediaOrigin.ICLOUD, MediaAvailability.CLOUD_ONLY, null, null, 0, 0, details = MediaDetails(burstId = burst),
        collectionIds = if (indexed) setOf(SmartCollection.BURSTS.id) else emptySet())
}
