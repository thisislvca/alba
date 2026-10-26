package me.lvca.alba.app.shared

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.gallery.GalleryState
import me.lvca.alba.app.sharing.SharedActivityState
import me.lvca.alba.app.ui.collections.CollectionsPage
import me.lvca.alba.app.ui.collections.CollectionsScreen
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.shared.LocalSharedActivity
import me.lvca.alba.engine.model.GalleryCollection
import me.lvca.alba.engine.model.SharedAlbumGeneration
import me.lvca.alba.engine.model.SharedAlbumInfo
import me.lvca.alba.engine.model.SharedAlbumRole
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedActivityIndicatorsTest {
    @get:Rule val compose = createComposeRule()
    private val info = SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.VIEWER)
    private val news = GalleryCollection("shared:test:shared:o:news", "With news", shared = info)
    private val quiet = GalleryCollection("shared:test:shared:o:quiet", "Quiet", shared = info)

    @Test fun sharedAlbumsShowUnreadDotsAndTheInvitationCount() {
        var invitationsOpened = 0
        compose.setContent { AlbaTheme(darkTheme = false) {
            CompositionLocalProvider(LocalSharedActivity provides SharedActivityState(setOf(news.id), unreadInvitations = 2)) {
                CollectionsScreen(GalleryState(collections = listOf(news, quiet)), canEdit = true, previewRetryVersion = 0, folder = null,
                    page = CollectionsPage.SHARED, summaries = emptyMap(), scroll = rememberLazyGridState(), preview = {},
                    openPage = {}, openFolder = {}, openCollection = {}, openOffline = {}, openSharedInvitations = { invitationsOpened++ },
                    createAlbum = {}, requestPhotos = {})
            }
        } }
        compose.onNodeWithTag("unread-${news.id}", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("unread-${quiet.id}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasText("2") and hasAnyAncestor(hasTestTag("shared-invitations-badge")), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("shared-invitations-button").performClick()
        compose.runOnIdle { assertEquals(1, invitationsOpened) }
    }

    @Test fun noInvitationBadgeWithoutUnseenInvitations() {
        compose.setContent { AlbaTheme(darkTheme = false) {
            CollectionsScreen(GalleryState(collections = listOf(quiet)), canEdit = true, previewRetryVersion = 0, folder = null,
                page = CollectionsPage.SHARED, summaries = emptyMap(), scroll = rememberLazyGridState(), preview = {},
                openPage = {}, openFolder = {}, openCollection = {}, openOffline = {}, createAlbum = {}, requestPhotos = {})
        } }
        compose.onNodeWithTag("shared-invitations-badge", useUnmergedTree = true).assertDoesNotExist()
    }
}
