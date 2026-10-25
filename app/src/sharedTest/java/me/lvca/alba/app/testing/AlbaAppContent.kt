package me.lvca.alba.app.testing

import androidx.compose.runtime.State
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import me.lvca.alba.app.DiagnosticsConsent
import me.lvca.alba.app.account.PhoneStorageState
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.actions.MediaWork
import me.lvca.alba.app.gallery.GalleryState
import me.lvca.alba.app.transfers.TransferState
import me.lvca.alba.app.ui.AlbaActions
import me.lvca.alba.app.ui.AlbaApp
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.engine.model.TransferId

/** Shows the production app UI for the given state, with test actions that report the callbacks a test observes. */
internal fun ComposeContentTestRule.setAlbaContent(
    gallery: GalleryState,
    session: SessionState,
    transfers: TransferState = TransferState(),
    onPhotos: () -> Unit = {},
    onPick: () -> Unit = {},
    onTrash: (String) -> Unit = {},
    onContinue: (TransferId) -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onStartDemo: () -> Unit = {},
    darkTheme: Boolean = false,
) {
    setContent {
        AlbaTheme(darkTheme = darkTheme) {
            AlbaApp(
                gallery = gallery,
                session = session,
                transfers = transfers,
                phoneStorage = PhoneStorageState(),
                mediaWork = MediaWork(),
                previewRetryVersion = 0,
                message = null,
                diagnostics = DiagnosticsConsent(),
                actions = testAlbaActions(requestDevicePhotos = onPhotos, pickUpload = onPick,
                    continuePastUnresolved = onContinue, moveVerifiedPhotoToTrash = onTrash,
                    openAccount = onOpenAccount, startDemo = onStartDemo),
            )
        }
    }
}

/**
 * Shows the production app UI for [gallery], which the test's [actions] change as the view model would, for tests that
 * select photos, open them or move between collections.
 */
internal fun ComposeContentTestRule.setAlbaContent(gallery: State<GalleryState>, session: SessionState, actions: AlbaActions) {
    setContent {
        AlbaTheme(darkTheme = false) {
            AlbaApp(
                gallery = gallery.value,
                session = session,
                transfers = TransferState(),
                phoneStorage = PhoneStorageState(),
                mediaWork = MediaWork(),
                previewRetryVersion = 0,
                message = null,
                diagnostics = DiagnosticsConsent(),
                actions = actions,
            )
        }
    }
}
