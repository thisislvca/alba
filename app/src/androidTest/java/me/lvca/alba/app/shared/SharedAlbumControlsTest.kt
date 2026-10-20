package me.lvca.alba.app.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.testAlbaActions
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.shared.SharedAlbumActivity
import me.lvca.alba.app.ui.shared.SharedAlbumControls
import me.lvca.alba.app.ui.shared.SharedInvitations
import me.lvca.alba.app.ui.shared.SharedPhotoControls
import me.lvca.alba.engine.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SharedAlbumControlsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)
    private val album = GalleryCollection("shared:test:private:owner:album", "Family test", shared = SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.OWNER))
    @Test fun incomingInvitationShowsAlbumAndRequiresExplicitJoin() {
        val invitation = SharedInvitation("invitation:test:legacy:album", "Invited family", SharedAlbumGeneration.LEGACY, true)
        var pending = true; val responses = mutableListOf<Pair<String, Boolean>>()
        compose.setContent { AlbaTheme(darkTheme = false) { SharedInvitations(testAlbaActions(pendingSharedInvitations = { if(pending) listOf(invitation) else emptyList() },
            respondSharedInvitation = { id, accept -> responses += id to accept; pending = false }), {}) } }
        compose.waitUntil { compose.onAllNodesWithText("Invited family").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.shared_accept)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.shared_accept_notice)).assertIsDisplayed()
        assertTrue(responses.isEmpty())
        compose.onNodeWithText(label(R.string.shared_confirm)).performClick()
        compose.waitUntil { responses.size == 1 && compose.onAllNodesWithText(label(R.string.shared_no_invitations)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(invitation.id to true, responses.single())
    }

    @Test fun invitationsRequireConfirmationAndShowPendingParticipant() {
        val album = GalleryCollection("shared:test:private:owner:album", "Family test", shared = SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.OWNER))
        var state = SharedAlbumManagement(album, listOf(SharedParticipant("owner", "You", SharedAlbumRole.OWNER, isCurrentUser = true)))
        val commands = mutableListOf<SharedCommand>()
        compose.setContent { AlbaTheme(darkTheme = false) { SharedAlbumControls(album, testAlbaActions(sharedManagement = { state }, changeSharedAlbum = { _, c ->
            commands += c
            if(c.action == SharedAction.INVITE) state = state.copy(participants = state.participants + SharedParticipant("guest", c.value, SharedAlbumRole.CONTRIBUTOR, pending = true))
        }), {}) } }
        compose.waitUntil { compose.onAllNodesWithTag("shared-invite-email").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("shared-invite-email").performScrollTo().performTextInput("family@example.com")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText(label(R.string.shared_invite)).performScrollTo().performClick()
        assertTrue(commands.isEmpty())
        compose.onNodeWithText(label(R.string.shared_confirm_access)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.shared_confirm)).performClick()
        compose.waitUntil { commands.size == 1 && compose.onAllNodesWithText(label(R.string.shared_person_contributor) + " · " + label(R.string.shared_invited)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("family@example.com").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("family@example.com").assertIsDisplayed()
    }
    @Test fun viewerCannotEditAlbumAndLeavingNeedsConfirmation() {
        val album = GalleryCollection("shared:test:shared:owner:album", "Read-only", shared = SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.VIEWER))
        val commands = mutableListOf<SharedCommand>()
        compose.setContent { AlbaTheme(darkTheme = false) { SharedAlbumControls(album, testAlbaActions(sharedManagement = { SharedAlbumManagement(album, emptyList()) }, changeSharedAlbum = { _, c -> commands += c }), {}) } }
        compose.waitUntil { compose.onAllNodesWithText(label(R.string.shared_leave)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("shared-invite-email").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.shared_delete_album)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.shared_leave)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.shared_confirm_leave)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.shared_cancel)).performClick()
        assertTrue(commands.isEmpty())
    }
    @Test fun commentsPostAndFailuresKeepDraftWithoutPretendingSuccess() {
        val album = GalleryCollection("shared:test:private:owner:album", "Family", shared = SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.OWNER))
        val media = GalleryMedia(id = album.id + ":photo", fileName = "test.jpg", capturedAtEpochMillis = System.currentTimeMillis(), width = 10, height = 10,
            origin = MediaOrigin.ICLOUD, availability = MediaAvailability.CLOUD_ONLY, previewReference = null, originalReference = null, accentStartArgb = 0L, accentEndArgb = 0L)
        val comments = mutableListOf<SharedComment>(); var fail = true
        compose.setContent { AlbaTheme(darkTheme = false) { SharedPhotoControls(media, testAlbaActions(sharedManagement = { SharedAlbumManagement(album, emptyList()) },
            sharedDiscussion = { SharedDiscussion(comments.toList(), true, SharedAlbumGeneration.MODERN) }, changeSharedAlbum = { _, c ->
                if(fail) error("offline")
                comments += SharedComment("comment", "—", c.value, 1L, true, true)
            }), {}) } }
        compose.waitUntil { compose.onAllNodesWithTag("shared-comment-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("shared-comment-input").performScrollTo().performTextInput("Hello from Android")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("shared-post-comment").performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithText(label(R.string.shared_action_failed)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("shared-comment-input").assertTextContains("Hello from Android")
        assertTrue(comments.isEmpty()); fail = false
        compose.onNodeWithTag("shared-post-comment").performScrollTo().performClick()
        compose.waitUntil { comments.size == 1 }
        compose.onNodeWithText(label(R.string.shared_you)).assertExists()
        compose.onNodeWithTag("shared-comment-input").assertTextContains("")
        compose.onNodeWithText("Hello from Android").performScrollTo().assertIsDisplayed()
    }

    @Test fun denyingExplainsBlockAndUnblockingDoesNotInvite() {
        val person = SharedAccessRequest("guest", "Test requester")
        var state = SharedAlbumManagement(album, emptyList(), requests = listOf(person))
        val changes = mutableListOf<SharedCommand>()
        compose.setContent { MaterialTheme { SharedAlbumControls(album, testAlbaActions(sharedManagement = { state }, changeSharedAlbum = { _, c ->
            changes += c
            state = when (c.action) {
                SharedAction.DENY_REQUEST -> state.copy(requests = emptyList(), blocked = listOf(person))
                SharedAction.UNBLOCK -> state.copy(blocked = emptyList())
                else -> error("Unexpected mutation")
            }
        }), {}) } }
        compose.waitUntil { compose.onAllNodesWithText(label(R.string.shared_deny)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.shared_deny)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.shared_confirm_deny)).assertIsDisplayed()
        assertTrue(changes.isEmpty())
        compose.onNodeWithText(label(R.string.shared_confirm)).performClick()
        compose.waitUntil { compose.onAllNodesWithText(label(R.string.shared_unblock)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.shared_unblock)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.shared_confirm_unblock)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.shared_confirm)).performClick()
        compose.waitUntil { changes.size == 2 && compose.onAllNodesWithText(label(R.string.shared_unblock)).fetchSemanticsNodes().isEmpty() }
        assertTrue(state.participants.isEmpty())
    }

    @Test fun sharedLinkIsPrefilledWithoutResolvingOrJoiningUntilRequested() {
        val link = "https://photos.icloud.com/shared/album/fixture"
        var resolves = 0; var joins = 0
        compose.setContent { MaterialTheme { SharedInvitations(testAlbaActions(resolveSharedInvitation = {
            resolves++; SharedInvitation("invitation", "Private family", SharedAlbumGeneration.MODERN)
        }, respondSharedInvitation = { _, _ -> joins++ }), dismiss = {}, initialUrl = link) } }
        compose.onNodeWithTag("shared-invitation-link").assertTextContains(link)
        compose.waitForIdle(); assertEquals(0, resolves); assertEquals(0, joins)
        compose.onNodeWithText(label(R.string.shared_invitation_preview)).performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithText("Private family").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.shared_accept)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.shared_accept_notice)).assertIsDisplayed(); assertEquals(0, joins)
        compose.onNodeWithText(label(R.string.shared_confirm)).performClick()
        compose.waitUntil { joins == 1 }
    }

    @Test fun activityPaginatesAndPostsToTheWholePost() {
        val one = SharedPost("post:${album.id}:one", "Owner", true, 1790205441441, "First post")
        val two = one.copy(id = "post:${album.id}:two", caption = "Earlier post")
        val requests = mutableListOf<Int>(); val writes = mutableListOf<SharedCommand>(); val comments = mutableListOf<SharedComment>()
        val actions = testAlbaActions(sharedActivity = { _, rank -> requests += rank; if(rank == 0) SharedActivityPage(listOf(one), 1) else SharedActivityPage(listOf(two), null) },
            sharedPostPhotos = { listOf("${album.id}:photo1", "${album.id}:photo2") },
            sharedManagement = { SharedAlbumManagement(album, emptyList()) }, sharedPostDiscussion = { SharedDiscussion(comments.toList(), true, SharedAlbumGeneration.MODERN) },
            changeSharedAlbum = { _, c -> writes += c; comments += SharedComment("comment", "Owner", c.value, 1790205441441, true, true) })
        compose.setContent { MaterialTheme { SharedAlbumActivity(album, emptyList(), actions, {}, {}, {}) } }
        compose.waitUntil { compose.onAllNodesWithText("First post").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.shared_load_more)).performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithText("Earlier post").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(0, 1), requests)
        compose.onAllNodesWithText(label(R.string.shared_post_discussion))[0].performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithTag("shared-comment-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("shared-comment-input").performScrollTo().performTextInput("Whole post discussion")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("shared-post-comment").performScrollTo().performClick()
        compose.waitUntil { writes.size == 1 }
        assertEquals(SharedAction.POST_COMMENT, writes.single().action)
        assertEquals(one.id, writes.single().subject)
        compose.onNodeWithText(label(R.string.shared_save_library)).assertDoesNotExist()
    }
}
