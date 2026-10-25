package me.lvca.alba.app.gallery

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.testing.setAlbaContent
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.protocol.account.ICloudAccountState
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

    @Test fun selectedTilesShowACheckCircle() {
        compose.setAlbaContent(GalleryState(items = items, selection = setOf(items[1].id)),
            SessionState(accountState = ICloudAccountState.Demo, isRefreshing = false))
        compose.onNodeWithTag("media-${items[1].id}").assertIsSelected()
        compose.onAllNodesWithTag("tile-selected", useUnmergedTree = true).assertCountEquals(1)
    }
}
