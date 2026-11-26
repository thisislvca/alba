package me.lvca.alba.app.shared

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.ui.collections.AlbumCard
import me.lvca.alba.app.ui.collections.AlbumHero
import me.lvca.alba.app.ui.collections.CollectionPreview
import me.lvca.alba.app.ui.designsystem.AccountIdentity
import me.lvca.alba.app.ui.designsystem.LocalAccountIdentity
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
import java.io.ByteArrayOutputStream
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

    @Test fun ownAlbumCardShowsTheAccountsAvatarAndOthersShowTheirOwnersMonogram() {
        val albums = listOf(
            album("mine", SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.OWNER)),
            album("theirs", SharedAlbumInfo(SharedAlbumGeneration.LEGACY, SharedAlbumRole.VIEWER, ownerName = "sample Owner")),
            album("unnamed", SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.CONTRIBUTOR)),
        )
        compose.setContent { AlbaTheme(darkTheme = false) {
            CompositionLocalProvider(LocalAccountIdentity provides AccountIdentity(null, "alex@example.com")) { Column {
                albums.forEach { AlbumCard(it, CollectionPreview(3), 0, {}) {} }
            } }
        } }
        // Your own album: the account's monogram (an email's first letter), as the header and Profile show it.
        compose.onNodeWithTag("shared-owner-avatar-${albums[0].id}", useUnmergedTree = true)
            .assertContentDescriptionEquals("Created by You").assert(hasAnyDescendant(hasText("A")))
        compose.onAllNodesWithTag("account-photo", useUnmergedTree = true).assertCountEquals(0)
        // Another owner: the given and family name's initials, as iCloud.com draws them.
        compose.onNodeWithTag("shared-owner-avatar-${albums[1].id}", useUnmergedTree = true)
            .assertContentDescriptionEquals("Created by sample Owner").assert(hasAnyDescendant(hasText("SO")))
        // iCloud named no one: a person silhouette on Apple's grey, as iCloud.com shows an unnamed owner.
        compose.onNodeWithTag("shared-owner-avatar-${albums[2].id}", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasTestTag("avatar-silhouette")))
    }

    @Test fun ownAlbumCardShowsTheAccountsPhotoWhenThereIsOne() {
        val photo = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(android.graphics.Color.rgb(83, 132, 161))
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
        }
        val mine = album("mine", SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.OWNER))
        compose.setContent { AlbaTheme(darkTheme = false) {
            CompositionLocalProvider(LocalAccountIdentity provides AccountIdentity(photo, "alex@example.com")) {
                AlbumCard(mine, CollectionPreview(3), 0, {}) {}
            }
        } }
        compose.waitUntil(SHORT_WAIT) { compose.onAllNodesWithTag("account-photo", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("shared-owner-avatar-${mine.id}", useUnmergedTree = true)
            .assertContentDescriptionEquals("Created by You").assert(hasAnyDescendant(hasTestTag("account-photo")))
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
