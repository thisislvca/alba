package dev.mela.app

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SharedInvitationActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    @Test fun invitationSurvivesRecreationAndWaitsForSignIn() {
        assertNull((context as MelaApplication).graph.accountManager.authorizedSession)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://photos.icloud.com/shared/album/fixture"), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.waitUntil(15000) { compose.onAllNodesWithText(context.getString(R.string.shared_invitation_sign_in)).fetchSemanticsNodes().isNotEmpty() }
            scenario.recreate()
            compose.onNodeWithText(context.getString(R.string.shared_invitation_sign_in)).assertIsDisplayed()
            assertNull((context as MelaApplication).graph.accountManager.authorizedSession)
        }
    }
    @Test fun warmSharedLinkIsDeliveredWithoutJoiningOrOpeningSignInForInvalidLinks() {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND; type = "text/plain"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_TEXT, "https://photos.icloud.com/shared/album/fixture")
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.waitUntil(15000) { compose.onAllNodesWithText(context.getString(R.string.shared_invitation_sign_in)).fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { activity ->
                activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND; type = "text/plain"
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(Intent.EXTRA_TEXT, "https://evil.test/shared/album/x")
                })
            }
            compose.waitUntil(15000) { compose.onAllNodesWithText(context.getString(R.string.shared_invalid_link)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(context.getString(R.string.shared_invalid_link)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.incoming_connect)).assertDoesNotExist()
            scenario.onActivity { assertEquals("https://evil.test/shared/album/x", it.intent.getStringExtra(Intent.EXTRA_TEXT)) }
            compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        }
    }
}
