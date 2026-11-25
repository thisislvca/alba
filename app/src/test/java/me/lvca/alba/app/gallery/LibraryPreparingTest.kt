package me.lvca.alba.app.gallery

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.DiagnosticsConsent
import me.lvca.alba.app.R
import me.lvca.alba.app.account.AccountInfo
import me.lvca.alba.app.account.PhoneStorageState
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.actions.MediaWork
import me.lvca.alba.app.screenshots.ScreenshotFixtures
import me.lvca.alba.app.testing.setAlbaContent
import me.lvca.alba.app.testing.testAlbaActions
import me.lvca.alba.app.transfers.TransferState
import me.lvca.alba.app.ui.AlbaApp
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.protocol.account.ICloudAccountState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * While Apple is still indexing the iCloud library, the Photos tab and the profile say so calmly for as long as it
 * lasts, the phone's own photos stay, and the library takes over by itself once indexing finishes.
 */
@RunWith(AndroidJUnit4::class)
class LibraryPreparingTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val phone = ScreenshotFixtures.library(6).map { it.copy(id = "phone-${it.id}", origin = MediaOrigin.DEVICE) }
    private val signedIn = SessionState(accountState = ICloudAccountState.SignedIn("alex@example.com"), isRefreshing = false)

    @Test
    fun aLibraryAppleIsIndexingShowsItsProgressAboveThePhonePhotos() {
        compose.setAlbaContent(GalleryState(items = phone, deviceCount = phone.size, deviceMediaAccess = true),
            signedIn.copy(libraryPreparing = LibraryPreparing(progress = 42)))

        compose.onNodeWithTag("library-preparing").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.library_preparing_title)).assertIsDisplayed()
        compose.onNodeWithTag("library-preparing-progress").assertTextEquals("42% ready")
        compose.onNodeWithTag("library-preparing-bar").assertExists()
        // The header says the same, not "iCloud Photos" over an empty-looking library.
        compose.onNodeWithText("Getting iCloud Photos ready… 42%").assertIsDisplayed()
        compose.onNodeWithTag("media-${phone.first().id}").assertExists()
        compose.onNodeWithText(context.getString(R.string.loading_photos)).assertDoesNotExist()
        // The summary counts the phone's photos only, rather than "0 iCloud photos".
        compose.onNodeWithText(context.getString(R.string.phone_photo_count, phone.size)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun withoutAProgressThereIsNoBarAndAnEmptyLibraryShowsOnlyTheNote() {
        compose.setAlbaContent(GalleryState(), signedIn.copy(libraryPreparing = LibraryPreparing()))

        compose.onNodeWithTag("library-preparing").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("library-preparing-bar").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.library_preparing_status)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.no_photos_in_this_view)).assertDoesNotExist()
    }

    @Test
    fun aQuietCheckKeepsTheNoteAndTheLibraryTakesOverOnceIndexingFinishes() {
        val gallery = mutableStateOf(GalleryState(items = phone, deviceCount = phone.size, deviceMediaAccess = true))
        val session = mutableStateOf(signedIn.copy(libraryPreparing = LibraryPreparing(progress = 90)))
        var checks = 0
        // Checking again is what Alba does every minute on screen; the second check finds the library ready.
        val actions = testAlbaActions(refresh = {
            checks++
            if (checks == 1) session.value = session.value.copy(isRefreshing = true)
            else {
                val cloud = ScreenshotFixtures.library(12)
                gallery.value = gallery.value.copy(items = cloud + phone, cloudCount = cloud.size)
                session.value = session.value.copy(isRefreshing = false, libraryPreparing = null)
            }
        })
        compose.setContent {
            AlbaTheme(darkTheme = false) {
                AlbaApp(gallery = gallery.value, session = session.value, transfers = TransferState(), phoneStorage = PhoneStorageState(),
                    mediaWork = MediaWork(), previewRetryVersion = 0, message = null, diagnostics = DiagnosticsConsent(), actions = actions)
            }
        }

        compose.onNodeWithTag("library-preparing-check").performClick()
        // A check under way keeps the note in place, says it is checking and shows no loading state over the photos.
        compose.onNodeWithTag("library-preparing").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.checking)).assertIsDisplayed()
        compose.onNodeWithTag("catalog-scan-bar").assertDoesNotExist()

        actions.refresh()
        compose.onNodeWithTag("library-preparing").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.library_preparing_status)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.icloud_photos)).assertIsDisplayed()
    }

    @Test
    fun theProfileSaysAppleIsGettingPhotosReadyInsteadOfNotChecked() {
        compose.setAlbaContent(GalleryState(), signedIn.copy(isAccountOpen = true, accountInfo = AccountInfo(),
            libraryPreparing = LibraryPreparing(progress = 10)))

        compose.onNodeWithTag("profile-photos-service").performScrollTo()
        compose.onNodeWithText(context.getString(R.string.photos_service_preparing)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.photos_service_has_not_been_checked)).assertDoesNotExist()
    }
}
