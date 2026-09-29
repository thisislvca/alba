package dev.mela.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.mela.app.ui.*
import dev.mela.app.ui.theme.MelaTheme
import dev.mela.engine.model.*
import dev.mela.protocol.account.ICloudAccountState
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SharedAlbumsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<MelaApplication>()
    private fun label(id: Int) = context.getString(id)
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        File(context.getExternalFilesDir("shared-albums")!!.apply { mkdirs() }, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun versionBadgeOpensAccessibleExplanationAndKeepsRoleDistinct() {
        val album = GalleryCollection("shared:test:legacy:owner:album", "Family archive", shared = SharedAlbumInfo(SharedAlbumGeneration.LEGACY, SharedAlbumRole.VIEWER))
        compose.setContent {
            var open by remember { mutableStateOf(false) }
            MelaTheme(darkTheme = true) { Surface(Modifier.fillMaxSize()) {
                SharedAlbumBadge(album.shared!!) { open = true }
                if(open) SharedAlbumDetails(album) { open = false }
            } }
        }
        compose.onNodeWithTag("shared-version-badge").performClick()
        compose.onNodeWithText(label(R.string.shared_legacy_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.shared_role_viewer)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.shared_copy_notice)).assertIsDisplayed()
        capture("legacy-details")
        compose.onNodeWithText(label(R.string.shared_understood)).performScrollTo().performClick()
        compose.onNodeWithTag("shared-album-details").assertDoesNotExist()
    }
    @Test fun modernDetailsExplainOwnerStorageAndContributionPickerUsesExplicitSelection() {
        val album = GalleryCollection("shared:test:private:owner:album", "Family moments", shared = SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.OWNER, participantCount = 1))
        var picked: List<String>? = null
        val media = GalleryMedia("fixture:test", "Family.jpg", 1, 10, 10, MediaOrigin.ICLOUD, MediaAvailability.CLOUD_ONLY, null, null, 0xff294e63, 0xff497187)
        compose.setContent {
            var picker by remember { mutableStateOf(false) }
            MelaTheme(darkTheme = true) {
                if(!picker) SharedAlbumDetails(album) { picker = true }
                else SharedContributionPicker(listOf(media), {}, {}, { picked = it })
            }
        }
        compose.onNodeWithText(label(R.string.shared_new_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.shared_role_owner)).assertIsDisplayed()
        capture("modern-details")
        compose.onNodeWithText(label(R.string.shared_understood)).performScrollTo().performClick()
        compose.onNodeWithTag("shared-select-fixture:test").performClick()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.shared_add_selected, 1, 1)).performClick()
        compose.runOnIdle { assertEquals(listOf(media.id), picked) }
    }
    @Test fun personalAlbumChooserNeverOffersASharedDestination() {
        compose.setContent { MelaTheme(darkTheme = false) { AlbumChooser(listOf(GalleryCollection("personal", "Personal"),
            GalleryCollection("shared:test:x:y:z", "Shared", shared = SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.OWNER))), {}, {}, {}) } }
        compose.onNodeWithText("Personal").assertIsDisplayed()
        compose.onNodeWithText("Shared").assertDoesNotExist()
    }
}
