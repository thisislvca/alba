package me.lvca.alba.app.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.ui.collections.AlbumCard
import me.lvca.alba.app.ui.collections.AlbumHero
import me.lvca.alba.app.ui.collections.CollectionPreview
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.shared.expiryDateLabel
import me.lvca.alba.engine.model.GalleryCollection
import me.lvca.alba.engine.model.SharedAlbumGeneration
import me.lvca.alba.engine.model.SharedAlbumInfo
import me.lvca.alba.engine.model.SharedAlbumRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class SharedAlbumLabelsTest {
    @get:Rule val compose = createComposeRule()

    private fun album(id: String, info: SharedAlbumInfo) = GalleryCollection("shared:test:x:y:$id", "Album $id", shared = info)

    @Test fun sharedAlbumTilesSayWhoCreatedOrJoinedThemAsICloudDoes() {
        val albums = listOf(
            album("mine", SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.OWNER)),
            album("one", SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.OWNER, sharedWith = listOf("Sample Guest"))),
            album("two", SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.OWNER, sharedWith = listOf("A", "B"))),
            album("theirs", SharedAlbumInfo(SharedAlbumGeneration.LEGACY, SharedAlbumRole.VIEWER, ownerName = "Sample Owner")),
            album("unnamed", SharedAlbumInfo(SharedAlbumGeneration.LEGACY, SharedAlbumRole.VIEWER)),
        )
        compose.setContent { AlbaTheme(darkTheme = false) { Column {
            albums.forEach { AlbumCard(it, CollectionPreview(3), 0, {}) {} }
        } } }
        val lines = compose.onAllNodesWithTag("shared-album-owner", useUnmergedTree = true).fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].joinToString { it.text } }
        assertEquals(listOf("Created by You", "Shared with Sample Guest", "Shared with 2 people", "From Sample Owner"), lines)
        compose.onNodeWithTag("shared-album-expiry", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aTemporaryAlbumSaysWhenItExpires() {
        val expires = ZonedDateTime.now().plusDays(1).toInstant().toEpochMilli()
        val info = SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.OWNER, expiresAt = expires)
        compose.setContent { AlbaTheme(darkTheme = false) { AlbumCard(album("temp", info), CollectionPreview(3), 0, {}) {} } }
        compose.onNodeWithTag("shared-album-expiry", useUnmergedTree = true)
            .assertTextEquals("Expires ${expiryDateLabel(expires, Locale.US)}")
    }

    @Test fun expiryLeavesOutThisYearAndFollowsTheLocale() {
        val zone = ZoneId.of("Europe/Rome")
        val now = ZonedDateTime.of(2026, 11, 25, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        // Apple's expiry is a moment; it is shown as the local day, here the evening of December 8.
        val expiry = ZonedDateTime.of(2026, 12, 8, 19, 44, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("December 8", expiryDateLabel(expiry, Locale.US, now, zone))
        assertEquals("8 dicembre", expiryDateLabel(expiry, Locale.ITALY, now, zone))
        val nextYear = ZonedDateTime.of(2027, 1, 3, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("January 3, 2027", expiryDateLabel(nextYear, Locale.US, now, zone))
    }

    @Test fun albumHeaderCountsItemsAndNamesTheCreatorInsteadOfTheAlbumGeneration() {
        val theirs = album("theirs", SharedAlbumInfo(SharedAlbumGeneration.LEGACY, SharedAlbumRole.CONTRIBUTOR, ownerName = "Sample Owner"))
        var opened = false
        compose.setContent { AlbaTheme(darkTheme = false) {
            AlbumHero(theirs, CollectionPreview(35), 0, {}, activity = null, view = null, info = { opened = true })
        } }
        compose.onNodeWithText("35 items · Created by Sample Owner", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Legacy", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("shared-version-badge").performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test fun ownAlbumHeaderSaysCreatedByYouAndItsExpiry() {
        val expires = ZonedDateTime.now().plusDays(1).toInstant().toEpochMilli()
        val mine = album("mine", SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.OWNER, expiresAt = expires))
        compose.setContent { AlbaTheme(darkTheme = false) { AlbumHero(mine, CollectionPreview(69), 0, {}, activity = null, view = null, info = {}) } }
        compose.onNodeWithText("69 items · Created by You", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("shared-album-expiry", useUnmergedTree = true)
            .assertTextEquals("Expires ${expiryDateLabel(expires, Locale.US)}")
    }
}
