package me.lvca.alba.app.gallery.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.gallery.BackupBadge
import me.lvca.alba.app.ui.gallery.LocalBackupBadges
import me.lvca.alba.app.ui.gallery.MediaTile
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaOrigin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A phone item that isn't in iCloud keeps its tile, with a cloud-off badge, until it is backed up. */
@RunWith(AndroidJUnit4::class)
class NotBackedUpBadgeTest {
    @get:Rule
    val compose = createComposeRule()

    private val media = GalleryMedia("device:external:7", "IMG_0007.JPG", 0, 4, 3, MediaOrigin.DEVICE,
        MediaAvailability.DEVICE_ORIGINAL, previewReference = null, originalReference = null,
        accentStartArgb = 0xFF263238, accentEndArgb = 0xFF455A64)

    private fun showTile(badges: Map<String, BackupBadge>, tile: GalleryMedia = media) = compose.setContent {
        val state = remember { mutableStateOf(badges) }
        AlbaTheme(darkTheme = false) {
            CompositionLocalProvider(LocalBackupBadges provides state) {
                Box(Modifier.size(120.dp)) { MediaTile(media = tile, onClick = {}, onVisible = {}, selectionAction = {}) }
            }
        }
    }

    @Test fun phoneOnlyItemShowsNotBackedUpOnItsTile() {
        showTile(mapOf(media.id to BackupBadge.NOT_BACKED_UP))
        compose.onNodeWithTag("not-backed-up-${media.id}", useUnmergedTree = true).assertIsDisplayed()
            .assertContentDescriptionEquals("Not backed up")
    }

    @Test fun backedUpOrUploadingItemsShowNoNotBackedUpBadge() {
        showTile(emptyMap())
        compose.onNodeWithTag("not-backed-up-${media.id}", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun uploadingItemShowsItsUploadBadgeInstead() {
        showTile(mapOf(media.id to BackupBadge.QUEUED))
        compose.onNodeWithTag("not-backed-up-${media.id}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("backup-badge-queued", useUnmergedTree = true).assertIsDisplayed()
    }
}
