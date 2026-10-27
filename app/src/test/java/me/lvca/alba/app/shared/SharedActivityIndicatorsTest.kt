package me.lvca.alba.app.shared

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.gallery.GalleryState
import me.lvca.alba.app.sharing.SharedActivityState
import me.lvca.alba.app.sharing.SharedOpenRequest
import me.lvca.alba.app.testing.testAlbaActions
import me.lvca.alba.app.ui.collections.CollectionsPage
import me.lvca.alba.app.ui.collections.CollectionsScreen
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.shared.LocalNotificationAccess
import me.lvca.alba.app.ui.shared.LocalSharedActivity
import me.lvca.alba.app.ui.shared.NotificationAccess
import me.lvca.alba.app.ui.shared.SharedActivityEffects
import me.lvca.alba.app.ui.shared.SharedNotificationsToggle
import me.lvca.alba.engine.model.GalleryCollection
import me.lvca.alba.engine.model.SharedAlbumGeneration
import me.lvca.alba.engine.model.SharedAlbumInfo
import me.lvca.alba.engine.model.SharedAlbumRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun withoutPermissionTheSwitchIsOffAsksInContextAndOffersSettings() {
        var requested = 0; var settings = 0; val asked = mutableListOf<Unit>(); val changes = mutableListOf<Boolean>()
        val actions = testAlbaActions(setSharedActivityNotifications = { changes += it }, sharedNotificationsAsked = { asked += Unit })
        compose.setContent { AlbaTheme(darkTheme = false) {
            CompositionLocalProvider(LocalNotificationAccess provides NotificationAccess(false, { requested++ }, { settings++ })) {
                SharedNotificationsToggle(actions)
            }
        } }
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNode(isToggleable()).performClick()
        compose.runOnIdle { assertEquals(1, requested); assertEquals(1, asked.size); assertTrue(changes.isEmpty()) }
        compose.onNodeWithTag("shared-allow-notifications").performClick()
        compose.runOnIdle { assertEquals(1, settings) }
    }

    @Test fun showingTheSharedAlbumsPageNeverRaisesAndroidsPermissionDialogAndANotificationOpensItsAlbum() {
        var requested = 0; var asked = 0; var consumed = 0; val opened = mutableListOf<String>()
        val actions = testAlbaActions(sharedNotificationsAsked = { asked++ }, consumeSharedOpen = { consumed++ })
        val state = SharedActivityState(open = SharedOpenRequest(news.id, false))
        compose.setContent { AlbaTheme(darkTheme = false) {
            CompositionLocalProvider(LocalSharedActivity provides state,
                LocalNotificationAccess provides NotificationAccess(false, { requested++ }, {})) {
                SharedActivityEffects(albumIds = setOf(news.id), actions = actions, openAlbum = { opened += it }) {}
            }
        } }
        compose.runOnIdle {
            assertEquals(listOf(news.id), opened); assertEquals(1, consumed)
            assertEquals(0, requested); assertEquals(0, asked)
        }
    }
}
