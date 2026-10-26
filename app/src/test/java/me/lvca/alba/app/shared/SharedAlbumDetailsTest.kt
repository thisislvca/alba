package me.lvca.alba.app.shared

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.shared.SharedAlbumBadge
import me.lvca.alba.app.ui.shared.SharedAlbumDetails
import me.lvca.alba.app.ui.shared.SharedAlbumEmptyState
import me.lvca.alba.app.ui.shared.SharedContributionPicker
import me.lvca.alba.engine.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedAlbumDetailsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<AlbaApplication>()
    private fun label(id: Int) = context.getString(id)
    @Test fun versionBadgeOpensAccessibleExplanationAndKeepsRoleDistinct() {
        val album = GalleryCollection("shared:test:legacy:owner:album", "Family archive", shared = SharedAlbumInfo(SharedAlbumGeneration.LEGACY, SharedAlbumRole.VIEWER))
        compose.setContent {
            var open by remember { mutableStateOf(false) }
            AlbaTheme(darkTheme = true) { Surface(Modifier.fillMaxSize()) {
                SharedAlbumBadge(album.shared!!) { open = true }
                if(open) SharedAlbumDetails(album) { open = false }
            } }
        }
        compose.onNodeWithTag("shared-version-badge").performClick()
        compose.onNodeWithText(label(R.string.shared_legacy_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.shared_role_viewer)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.shared_copy_notice)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.shared_understood)).performScrollTo().performClick()
        compose.onNodeWithTag("shared-album-details").assertDoesNotExist()
    }
    @Test fun modernDetailsExplainOwnerStorageAndContributionPickerUsesExplicitSelection() {
        val album = GalleryCollection("shared:test:private:owner:album", "Family moments", shared = SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.OWNER, participantCount = 1))
        var picked: List<String>? = null
        val media = GalleryMedia("fixture:test", "Family.jpg", 1, 10, 10, MediaOrigin.ICLOUD, MediaAvailability.CLOUD_ONLY, null, null, 0xff294e63, 0xff497187)
        compose.setContent {
            var picker by remember { mutableStateOf(false) }
            AlbaTheme(darkTheme = true) {
                if(!picker) SharedAlbumDetails(album) { picker = true }
                else SharedContributionPicker(listOf(media), {}, {}, { ids, _ -> picked = ids })
            }
        }
        compose.onNodeWithText(label(R.string.shared_new_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.shared_role_owner)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.shared_understood)).performScrollTo().performClick()
        compose.onNodeWithTag("shared-select-fixture:test").performClick()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.shared_add_selected, 1, 1)).performClick()
        compose.runOnIdle { assertNull(picked) }
        compose.onNodeWithTag("shared-post-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf(media.id), picked) }
    }

    @Test fun postSheetSendsTheCaptionOnlyWhereTheAlbumKeepsOne() {
        val media = GalleryMedia("fixture:test", "Family.jpg", 1, 10, 10, MediaOrigin.ICLOUD, MediaAvailability.CLOUD_ONLY, null, null, 0xff294e63, 0xff497187)
        val posts = mutableListOf<Pair<List<String>, String>>()
        var legacy by mutableStateOf(false)
        compose.setContent { AlbaTheme(darkTheme = false) { key(legacy) {
            SharedContributionPicker(listOf(media), {}, {}, { ids, caption -> posts += ids to caption }, legacy = legacy,
                excludedLivePhotos = if (legacy) 2 else 0)
        } } }
        compose.onNodeWithTag("shared-select-fixture:test").performClick()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.shared_add_selected, 1, 1)).performClick()
        compose.onAllNodesWithText(context.resources.getQuantityString(R.plurals.shared_post_items_title, 1, 1)).onFirst().assertExists()
        compose.onNodeWithTag("shared-caption").performTextInput("Beach day")
        compose.onNodeWithTag("shared-post-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf(listOf(media.id) to "Beach day"), posts) }

        legacy = true
        compose.onNodeWithTag("shared-live-photos-excluded").assertTextEquals(context.resources.getQuantityString(R.plurals.shared_live_photos_excluded, 2, 2))
        compose.onNodeWithTag("shared-select-fixture:test").performClick()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.shared_add_selected, 1, 1)).performClick()
        compose.onNodeWithTag("shared-caption").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.shared_caption_legacy)).assertIsDisplayed()
        compose.onNodeWithTag("shared-post-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf(media.id) to "", posts.last()) }
    }

    @Test fun emptySharedAlbumOffersAddingPhotosAndInvitingPeople() {
        var added = 0; var invited = 0
        compose.setContent { AlbaTheme(darkTheme = false) { SharedAlbumEmptyState({ added++ }, { invited++ }) } }
        compose.onNodeWithText(label(R.string.shared_empty_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.shared_add_photos)).performClick()
        compose.onNodeWithText(label(R.string.shared_invite_people)).performClick()
        compose.runOnIdle { assertEquals(1, added); assertEquals(1, invited) }
    }
}
