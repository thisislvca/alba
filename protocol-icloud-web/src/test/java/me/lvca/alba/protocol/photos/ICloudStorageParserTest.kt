package me.lvca.alba.protocol.photos

import org.junit.Assert.assertEquals
import org.junit.Test

/** Apple's storageUsageInfo for a shared Family plan: the plan, the account's own categories and the family's share. */
class ICloudStorageParserTest {
    private fun fixture() = requireNotNull(javaClass.getResource("/photos/storage-usage-family.json")).readText()

    @Test fun familyPlanKeepsApplesExactBytesAndTheFamilyShare() {
        val storage = ICloudStorageParser.parse(fixture(), 7)
        // Exactly 2 TiB, which Apple calls "2 TB".
        assertEquals(2L shl 40, storage.totalBytes)
        assertEquals(1_948_000_000_000, storage.usedBytes)
        assertEquals(1_470_000_000_000, storage.familyBytes)
        assertEquals(listOf("Photos and Videos", "Backup", "Documents", "Messages", "Mail"), storage.categories.map { it.name })
        assertEquals(472_000_000_000, storage.categories.sumOf { it.bytes })
        assertEquals(7L, storage.checkedAtEpochMillis)
    }

    @Test fun anAccountWithoutFamilySharingHasNoFamilyShare() {
        val storage = ICloudStorageParser.parse("""{"storageUsageInfo":{"usedStorageInBytes":10,"totalStorageInBytes":20},
            "storageUsageByMedia":[{"displayLabel":"Photos","usageInBytes":10}]}""")
        assertEquals(0L, storage.familyBytes)
        assertEquals("Photos", storage.categories.single().name)
    }

    @Test fun aFamilyEntryAmongTheMediaBecomesTheFamilyShareNotACategory() {
        val storage = ICloudStorageParser.parse("""{"storageUsageInfo":{"usedStorageInBytes":30,"totalStorageInBytes":40},
            "storageUsageByMedia":[{"mediaKey":"photos","displayLabel":"Photos","usageInBytes":10},
            {"mediaKey":"family","displayLabel":"Family","usageInBytes":20}]}""")
        assertEquals(20L, storage.familyBytes)
        assertEquals(listOf("Photos"), storage.categories.map { it.name })
    }

    @Test fun familyInfoWinsOverAFamilyMediaEntryAndNegativeUsageIsIgnored() {
        val both = ICloudStorageParser.parse("""{"storageUsageInfo":{"usedStorageInBytes":30,"totalStorageInBytes":40},
            "storageUsageByMedia":[{"mediaKey":"family","displayLabel":"Family","usageInBytes":20}],
            "familyStorageUsageInfo":{"usageInBytes":25}}""")
        assertEquals(25L, both.familyBytes)
        val negative = ICloudStorageParser.parse("""{"storageUsageInfo":{"usedStorageInBytes":30,"totalStorageInBytes":40},
            "familyStorageUsageInfo":{"usageInBytes":-5}}""")
        assertEquals(0L, negative.familyBytes)
    }
}
