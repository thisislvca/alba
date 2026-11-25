package me.lvca.alba.app.account

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertAny
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.platform.NetworkStatus
import me.lvca.alba.app.ui.account.ProfileStorage
import me.lvca.alba.app.ui.account.StorageSegment
import me.lvca.alba.app.ui.account.icloudSize
import me.lvca.alba.app.ui.account.storageSegments
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.engine.model.CloudStorageUsage
import me.lvca.alba.engine.model.StorageCategory
import me.lvca.alba.protocol.account.ICloudAccountState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** iCloud storage in Apple's units, and a usage bar and legend that account for every used byte, the family's included. */
@RunWith(AndroidJUnit4::class)
class ICloudStorageTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<AlbaApplication>()
    private val legendRow = SemanticsMatcher("an iCloud storage legend row") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("storage-legend-") == true
    }

    private fun size(bytes: Long, language: String = "en"): String {
        val config = Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(language)) }
        return app.createConfigurationContext(config).resources.icloudSize(bytes)
    }

    // A shared 2 TB Family plan, as Apple reports one: invented figures, Apple's shape.
    private val family = CloudStorageUsage(
        usedBytes = 1_948_000_000_000, totalBytes = 2L shl 40,
        categories = listOf(StorageCategory("Photos", 380_000_000_000), StorageCategory("Backup", 60_000_000_000),
            StorageCategory("Documents", 21_000_000_000), StorageCategory("Messages", 9_000_000_000), StorageCategory("Mail", 2_000_000_000)),
        checkedAtEpochMillis = 1_790_000_000_000, familyBytes = 1_470_000_000_000,
    )

    @Test fun plansReadAsAppleNamesThem() {
        assertEquals("2 TB", size(2L shl 40))
        assertEquals("1 TB", size(1L shl 40))
        assertEquals("200 GB", size(200L shl 30))
        assertEquals("50 GB", size(50L shl 30))
        assertEquals("5 GB", size(5L shl 30))
    }

    @Test fun usageCountsInThousandTwentyFours() {
        // Decimal formatting would call these 2 TB, 93 GB and 454 GB.
        assertEquals("1.8 TB", size(2_000_000_000_000))
        assertEquals("86.6 GB", size(93_000_000_000))
        assertEquals("423 GB", size(454_000_000_000))
        assertEquals("1.5 MB", size(1_572_864))
        assertEquals("512 bytes", size(512))
        assertEquals("0 bytes", size(0))
        // Rounding up to 1024 of a unit moves to the next one.
        assertEquals("1 TB", size((1L shl 40) - (1L shl 20)))
    }

    @Test fun italianKeepsItsDecimalCommaAndApplesUnits() {
        assertEquals("1,8 TB", size(2_000_000_000_000, "it"))
        assertEquals("2 TB", size(2L shl 40, "it"))
        assertEquals("512 byte", size(512, "it"))
    }

    @Test fun segmentsAddUpToUsedWithFamilyAfterTheAccountsOwnCategories() {
        val segments = family.storageSegments()
        assertEquals(family.usedBytes, segments.sumOf { it.bytes })
        assertEquals(listOf("Photos", "Backup", "Documents", "Messages", "Mail"),
            segments.filterIsInstance<StorageSegment.Category>().map { it.name })
        assertEquals(StorageSegment.Family(1_470_000_000_000), segments[5])
        assertEquals(StorageSegment.Other(6_000_000_000), segments.last())
    }

    @Test fun anOverrunningFamilyFigureIsTrimmedSoNothingIsCountedTwice() {
        // Apple's figures are read at slightly different moments; together they can exceed what is used.
        val segments = family.copy(familyBytes = 1_480_000_000_000).storageSegments()
        assertEquals(family.usedBytes, segments.sumOf { it.bytes })
        assertEquals(StorageSegment.Family(1_476_000_000_000), segments.last())
    }

    @Test fun applesOwnOtherJoinsTheRemainderAsOneOther() {
        val usage = CloudStorageUsage(100, 200, listOf(StorageCategory("Photos", 60), StorageCategory("Other", 10)), 0)
        assertEquals(listOf(StorageSegment.Category("Photos", 60), StorageSegment.Other(40)), usage.storageSegments())
    }

    @Test fun legendNamesFamilyAndOtherBesideTheAccountsCategories() {
        compose.setContent {
            AlbaTheme(darkTheme = false) {
                ProfileStorage(SessionState(accountState = ICloudAccountState.SignedIn("alex@example.com"),
                    network = NetworkStatus(true, false), accountInfo = AccountInfo(storage = family)),
                    demo = false, hasAccount = true, check = {})
            }
        }
        compose.onNodeWithText("1.8 TB of 2 TB used").assertExists()
        compose.onNodeWithText("234 GB available").assertExists()
        compose.onNodeWithTag("storage-legend-family", useUnmergedTree = true).onChildren().run {
            assertAny(hasText("Family"))
            assertAny(hasText(size(1_470_000_000_000)))
        }
        compose.onNodeWithTag("storage-legend-other", useUnmergedTree = true).onChildren().assertAny(hasText(size(6_000_000_000)))
        compose.onNodeWithTag("storage-legend-category-Photos", useUnmergedTree = true).onChildren().assertAny(hasText("Photos"))
        // One row per segment: five categories, Family and Other.
        assertEquals(7, compose.onAllNodes(legendRow, useUnmergedTree = true).fetchSemanticsNodes().size)
    }
}
