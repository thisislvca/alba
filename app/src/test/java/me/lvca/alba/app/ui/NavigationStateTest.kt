package me.lvca.alba.app.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import me.lvca.alba.app.GalleryState
import me.lvca.alba.app.RootDialog
import me.lvca.alba.app.SessionState
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationStateTest {
    private val scope = SaverScope { true }
    private fun <T> Saver<T, out Any>.roundTrip(value: T): T? {
        @Suppress("UNCHECKED_CAST")
        val saver = this as Saver<T, Any>
        val saved = with(saver) { scope.save(value) } ?: return null
        return saver.restore(saved)
    }

    @Test fun everyGallerySheetSurvivesRecreation() {
        listOf(GallerySheet.Filters, GallerySheet.Activity, GallerySheet.JumpToDate, GallerySheet.SharedInvitations,
            GallerySheet.SharedPicker, GallerySheet.SharedActivity, GallerySheet.SharedManage, GallerySheet.SharedInfo,
            GallerySheet.CreateAlbum, GallerySheet.CreateSharedAlbum, GallerySheet.RenameAlbum("album:1"),
            GallerySheet.DeleteAlbum("album:rename:2")).forEach { sheet ->
            assertEquals(sheet, GallerySheet.Saver.roundTrip(sheet))
        }
        assertNull(GallerySheet.Saver.roundTrip(null))
    }

    @Test fun rootDialogsSurviveRecreation() {
        listOf(RootDialog.DemoUpload, RootDialog.ConfirmCloudTrash(listOf("a", "b"), trashed = false),
            RootDialog.ConfirmRemoveFromAlbum("album", listOf("a"))).forEach { dialog ->
            assertEquals(dialog, RootDialog.Saver.roundTrip(dialog))
        }
    }

    @Test fun destinationFollowsTheAccountScreenThenTheOpenPhoto() {
        val photo = GalleryMedia("icloud:1", "1.jpg", 0, 1, 1, MediaOrigin.ICLOUD, MediaAvailability.CLOUD_ONLY, null, null, 0, 0)
        val gallery = GalleryState(items = listOf(photo), selectedMedia = photo)
        assertEquals(Destination.Account, Destination.of(SessionState(isAccountOpen = true), gallery))
        assertEquals(Destination.Media(photo, listOf(photo)), Destination.of(SessionState(), gallery))
        assertEquals(Destination.Gallery, Destination.of(SessionState(), GalleryState(items = listOf(photo))))
    }

    @Test fun backClearsTheSelectionBeforeLeavingAScreen() {
        assertEquals(RootBack.CLEAR_SELECTION, rootBack(Destination.Account, hasSelection = true))
        assertEquals(RootBack.CLOSE_ACCOUNT, rootBack(Destination.Account, hasSelection = false))
        assertNull(rootBack(Destination.Gallery, hasSelection = false))
    }
}
