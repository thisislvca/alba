package dev.mela.app

import dev.mela.app.ui.theme.MelaTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.mela.app.ui.*
import dev.mela.engine.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SharedAlbumControlsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)
    private fun capture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        val dir = context.getExternalFilesDir("shared-controls")!!.apply { mkdirs() }
        java.io.File(dir,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
    @Test fun incomingInvitationShowsAlbumAndRequiresExplicitJoin() {
        val invitation = SharedInvitation("invitation:test:legacy:album","Invited family",SharedAlbumGeneration.LEGACY,true)
        var pending = true; val responses = mutableListOf<Pair<String,Boolean>>()
        compose.setContent { MelaTheme(darkTheme = false) { SharedInvitations(LibraryActions(pendingSharedInvitations={ if(pending) listOf(invitation) else emptyList() },
            respondSharedInvitation={id,accept->responses+=id to accept;pending=false}),{}) } }
        compose.waitUntil { compose.onAllNodesWithText("Invited family").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.shared_accept)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.shared_accept_notice)).assertIsDisplayed()
        assertTrue(responses.isEmpty())
        compose.onNodeWithText(label(R.string.shared_confirm)).performClick()
        compose.waitUntil { responses.size == 1 && compose.onAllNodesWithText(label(R.string.shared_no_invitations)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(invitation.id to true,responses.single())
        capture("invitations-accepted")
    }

    @Test fun invitationsRequireConfirmationAndShowPendingParticipant() {
        val album = GalleryCollection("shared:test:private:owner:album", "Family test", shared = SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.OWNER))
        var state = SharedAlbumManagement(album, listOf(SharedParticipant("owner","You",SharedAlbumRole.OWNER,isCurrentUser=true)))
        val commands = mutableListOf<SharedCommand>()
        compose.setContent { MelaTheme(darkTheme = false) { SharedAlbumControls(album, LibraryActions(sharedManagement={ state }, changeSharedAlbum={ _, c ->
            commands += c
            if(c.action == SharedAction.INVITE) state = state.copy(participants=state.participants+SharedParticipant("guest",c.value,SharedAlbumRole.CONTRIBUTOR,pending=true))
        }), {}) } }
        compose.waitUntil { compose.onAllNodesWithTag("shared-invite-email").fetchSemanticsNodes().isNotEmpty() }
        capture("management-overview")
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
        capture("management-invited")
    }
    @Test fun viewerCannotEditAlbumAndLeavingNeedsConfirmation() {
        val album = GalleryCollection("shared:test:shared:owner:album", "Read-only", shared = SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.VIEWER))
        val commands = mutableListOf<SharedCommand>()
        compose.setContent { MelaTheme(darkTheme = false) { SharedAlbumControls(album,LibraryActions(sharedManagement={ SharedAlbumManagement(album,emptyList()) },changeSharedAlbum={_,c->commands+=c}),{}) } }
        compose.waitUntil { compose.onAllNodesWithText(label(R.string.shared_leave)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("shared-invite-email").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.shared_delete_album)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.shared_leave)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.shared_confirm_leave)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.shared_cancel)).performClick()
        assertTrue(commands.isEmpty())
        capture("viewer-permissions")
    }
    @Test fun commentsPostAndFailuresKeepDraftWithoutPretendingSuccess() {
        val album = GalleryCollection("shared:test:private:owner:album","Family",shared=SharedAlbumInfo(SharedAlbumGeneration.MODERN,SharedAlbumRole.OWNER))
        val media = GalleryMedia(id=album.id+":photo",fileName="test.jpg",capturedAtEpochMillis=System.currentTimeMillis(),width=10,height=10,
            origin=MediaOrigin.ICLOUD,availability=MediaAvailability.CLOUD_ONLY,previewReference=null,originalReference=null,accentStartArgb=0L,accentEndArgb=0L)
        val comments = mutableListOf<SharedComment>(); var fail = true
        compose.setContent { MelaTheme(darkTheme = false) { SharedPhotoControls(media,LibraryActions(sharedManagement={SharedAlbumManagement(album,emptyList())},
            sharedDiscussion={SharedDiscussion(comments.toList(),true,SharedAlbumGeneration.MODERN)},changeSharedAlbum={_,c->
                if(fail) error("offline")
                comments+=SharedComment("comment","—",c.value,1L,true,true)
            }),{}) } }
        compose.waitUntil { compose.onAllNodesWithTag("shared-comment-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("shared-comment-input").performScrollTo().performTextInput("Hello from Android")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("shared-post-comment").performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithText(label(R.string.shared_action_failed)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("shared-comment-input").assertTextContains("Hello from Android")
        assertTrue(comments.isEmpty()); fail=false
        compose.onNodeWithTag("shared-post-comment").performScrollTo().performClick()
        compose.waitUntil { comments.size == 1 }
        compose.onNodeWithText(label(R.string.shared_you)).assertExists()
        compose.onNodeWithTag("shared-comment-input").assertTextContains("")
        compose.onNodeWithText("Hello from Android").performScrollTo().assertIsDisplayed()
        capture("posted-comment")
    }
}
