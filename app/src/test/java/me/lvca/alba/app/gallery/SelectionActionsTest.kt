package me.lvca.alba.app.gallery

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
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
import org.junit.Assert.assertTrue
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

    @Test fun selectionCirclesWaitForSelectionModeAndTheBadgeMarksOnlyTheChosen() {
        val actions = GalleryStateActions(GalleryState(items = items))
        compose.setAlbaContent(actions.state, SessionState(accountState = ICloudAccountState.Demo, isRefreshing = false), actions)
        compose.onNodeWithTag("media-${items[0].id}").assertIsDisplayed()
        compose.onAllNodesWithTag("tile-selection-ring", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("tile-selected", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("media-${items[0].id}").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onAllNodesWithTag("tile-selected", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithTag("tile-selection-ring", useUnmergedTree = true).assertCountEquals(items.size - 1)
        // The badge sits in the tile's top-start corner, as in Google Photos.
        val tile = compose.onNodeWithTag("media-${items[0].id}").fetchSemanticsNode().boundsInRoot
        val badge = compose.onNodeWithTag("tile-selected", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("badge $badge is not at the start of $tile", badge.center.x < tile.center.x && badge.center.y < tile.center.y)
    }

    @Test fun moreOpensTheSharedFloatingMenuWhichClosesOnChoice() {
        val favorites = mutableListOf<Pair<List<String>, Boolean>>()
        val actions = object : GalleryStateActions(GalleryState(items = items, selection = setOf(items[0].id))) {
            override fun setFavorites(mediaIds: List<String>, favorite: Boolean) { favorites += mediaIds to favorite }
        }
        compose.setAlbaContent(actions.state, SessionState(accountState = ICloudAccountState.Demo, isRefreshing = false), actions)
        compose.onNodeWithTag("selection-toolbar").assertIsDisplayed()
        compose.onNodeWithTag("selection-more").performClick()
        val menu = compose.onNodeWithTag("selection-more-menu").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        // At least Photos' menu width, so short menus don't shrink to their longest label.
        assertTrue("menu is ${menu.width}px wide", menu.width >= with(compose.density) { 227.dp.toPx() } - 1f)
        compose.onNodeWithText("Favorite").performClick()
        compose.onNodeWithTag("selection-more-menu").assertDoesNotExist()
        assertEquals(listOf(listOf(items[0].id) to true), favorites)
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
