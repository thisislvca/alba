package dev.mela.app

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.mela.app.ui.*
import dev.mela.engine.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SharedCompletionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)
    private val album = GalleryCollection("shared:test:private:owner:album", "Family test", shared = SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.OWNER))
    private fun capture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        val dir = context.getExternalFilesDir("shared-completion")!!.apply { mkdirs() }
        java.io.File(dir,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
    @Test fun denyingExplainsBlockAndUnblockingDoesNotInvite() {
        val person = SharedAccessRequest("guest", "Test requester")
        var state = SharedAlbumManagement(album, emptyList(), requests = listOf(person))
        val changes = mutableListOf<SharedCommand>()
        compose.setContent { MaterialTheme { SharedAlbumControls(album, LibraryActions(sharedManagement = { state }, changeSharedAlbum = { _, c ->
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
        capture("unblock-confirmation")
        compose.onNodeWithText(label(R.string.shared_confirm)).performClick()
        compose.waitUntil { changes.size == 2 && compose.onAllNodesWithText(label(R.string.shared_unblock)).fetchSemanticsNodes().isEmpty() }
        assertTrue(state.participants.isEmpty())
    }
    @Test fun sharedLinkIsPrefilledWithoutResolvingOrJoiningUntilRequested() {
        val link = "https://photos.icloud.com/shared/album/fixture"
        var resolves = 0; var joins = 0
        compose.setContent { MaterialTheme { SharedInvitations(LibraryActions(resolveSharedInvitation = {
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
        val actions = LibraryActions(sharedActivity = { _, rank -> requests += rank; if(rank == 0) SharedActivityPage(listOf(one), 1) else SharedActivityPage(listOf(two), null) },
            sharedPostPhotos = { listOf("${album.id}:photo1", "${album.id}:photo2") },
            sharedManagement = { SharedAlbumManagement(album, emptyList()) }, sharedPostDiscussion = { SharedDiscussion(comments.toList(), true, SharedAlbumGeneration.MODERN) },
            changeSharedAlbum = { _, c -> writes += c; comments += SharedComment("comment", "Owner", c.value, 1790205441441, true, true) })
        compose.setContent { MaterialTheme { SharedAlbumActivity(album, emptyList(), actions, {}, {}, {}) } }
        compose.waitUntil { compose.onAllNodesWithText("First post").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.shared_load_more)).performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithText("Earlier post").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(0,1), requests)
        capture("album-activity")
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
    @Test fun incomingLinkIntentValidationDoesNotConfuseMediaOrUntrustedUrls() {
        val link = "https://photos.icloud.com/shared/album/fixture"
        assertEquals(link,incomingSharedInvitation(Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT,"Album\n$link") }))
        assertEquals(link,incomingSharedInvitation(Intent(Intent.ACTION_VIEW,Uri.parse(link))))
        assertEquals("",incomingSharedInvitation(Intent(Intent.ACTION_VIEW,Uri.parse("https://evil.test/shared/album/fixture"))))
        assertNull(incomingSharedInvitation(Intent(Intent.ACTION_VIEW,Uri.parse("content://gallery/photo"))))
    }
}
