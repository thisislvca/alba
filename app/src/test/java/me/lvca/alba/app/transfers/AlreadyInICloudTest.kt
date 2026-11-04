package me.lvca.alba.app.transfers

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.transfers.TransferQueueCard
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.TransferViewState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A backup item found already in iCloud says so where backup states show, rather than claiming it was uploaded. */
@RunWith(AndroidJUnit4::class)
class AlreadyInICloudTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theQueueSaysAnItemFoundInICloudIsAlreadyThereAndOneBeingCheckedIsWaitingForThat() {
        compose.setContent {
            AlbaTheme(darkTheme = false) {
                TransferQueueCard(
                    transfers = listOf(
                        transfer("one", "IMG_0001.HEIC", TransferViewState.VERIFIED, StoredMessage.ALREADY_IN_ICLOUD, already = true),
                        transfer("two", "IMG_0002.HEIC", TransferViewState.WAITING, StoredMessage.CHECKING_ICLOUD_LIBRARY_FIRST),
                    ),
                    onContinuePastUnresolved = {},
                )
            }
        }

        compose.onNodeWithText("Already in iCloud").assertIsDisplayed()
        compose.onNodeWithText("Checking whether it is already in iCloud").assertIsDisplayed()
    }

    private fun transfer(id: String, name: String, state: TransferViewState, message: StoredMessage, already: Boolean = false) =
        TransferView(TransferId(id), "device:$id", name, state, 10, 1, message = message.code, automatic = true, alreadyInICloud = already)
}
