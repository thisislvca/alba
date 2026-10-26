package me.lvca.alba.app.gallery

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.testing.GalleryStateActions
import me.lvca.alba.app.testing.setAlbaContent
import me.lvca.alba.app.testing.testAlbaActions
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.protocol.account.ICloudAccountState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectionActionsTest {
    @get:Rule
    val compose = createComposeRule()

    private val items = List(3) { index ->
        GalleryMedia("fixture:selection:$index", "IMG_$index.JPG", 1_780_000_000_000 - index * 60_000L, 4, 3, MediaOrigin.ICLOUD,
            MediaAvailability.CLOUD_ONLY, previewReference = null, originalReference = null,
            accentStartArgb = 0xFF263238, accentEndArgb = 0xFF455A64)
    }

    @Test fun selectionActionsFitWithoutTruncationAndKeepTheirFullNames() {
        compose.setAlbaContent(GalleryState(items = items, selection = setOf(items[0].id)),
            SessionState(accountState = ICloudAccountState.Demo, isRefreshing = false))
        listOf("Share", "Offline", "Save", "More").forEach { label ->
            val text = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
            val layout = mutableListOf<TextLayoutResult>()
            text.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layout)
            val result = layout.single()
            assertFalse("$label is truncated", result.multiParagraph.didExceedMaxLines || result.isLineEllipsized(0))
        }
        compose.onNodeWithContentDescription("Save to phone gallery").assertHasClickAction()
        compose.onNodeWithContentDescription("Keep offline").assertHasClickAction()
    }

    @Test fun aSelectionOfPhoneAndICloudItemsCanBeTrashedTogether() {
        val phone = GalleryMedia("device:1", "PXL_1.jpg", 1_780_000_000_000, 4, 3, MediaOrigin.DEVICE,
            MediaAvailability.DEVICE_ORIGINAL, previewReference = null, originalReference = "content://media/external/images/media/1",
            accentStartArgb = 0xFF263238, accentEndArgb = 0xFF455A64)
        val trashed = mutableListOf<List<String>>()
        val actions = GalleryStateActions(GalleryState(items = items + phone, selection = setOf(items[0].id, phone.id)),
            testAlbaActions(confirmMixedTrash = { selected -> trashed += selected.map { it.id } }))
        compose.setAlbaContent(actions.state, SessionState(accountState = ICloudAccountState.Demo, isRefreshing = false), actions)
        compose.onNodeWithContentDescription("More").performClick()
        // Neither single-trash entry fits a mixed selection; the combined one does.
        compose.onNodeWithText("Move to iCloud trash").assertDoesNotExist()
        compose.onNodeWithTag("trash-mixed-selection").performClick()
        assertEquals(listOf(listOf(items[0].id, phone.id)), trashed)
    }
}
