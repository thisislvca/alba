package me.lvca.alba.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.ui.GridAnchor
import me.lvca.alba.app.ui.KeepGridAnchor
import org.junit.Rule
import org.junit.Test

class GridAnchorTest {
    @get:Rule val compose = createComposeRule()

    /** A restored LazyGridState holds only an index; measured against fewer items it is clamped and the scroll is lost. */
    @Test fun restoredScrollPositionSurvivesBeingMeasuredAgainstFewerItems() {
        val ids = (0 until 60).map { "m$it" }
        val loaded = mutableStateOf(true)
        var compositions = 0
        val tester = StateRestorationTester(compose)
        tester.setContent {
            val grid = rememberLazyGridState()
            val anchor = rememberSaveable(saver = GridAnchor.Saver) { GridAnchor() }
            // The restored composition starts with fewer items than the saved index, as when another tab's results are still showing.
            val restored = remember { compositions++ > 0 }
            val current = if (restored && !loaded.value) ids.take(2) else ids
            KeepGridAnchor(grid, anchor, remember(current) { current.withIndex().associate { it.value to it.index } }) { current.indexOf(it) }
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.size(300.dp).testTag("grid"), state = grid) {
                items(current, key = { it }) { Box(Modifier.height(100.dp).testTag(it)) }
            }
        }
        compose.onNodeWithTag("grid").performScrollToIndex(30)
        compose.onNodeWithTag("m30").assertIsDisplayed()
        loaded.value = false
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        loaded.value = true
        compose.waitForIdle()
        compose.onNodeWithTag("m30").assertIsDisplayed()
    }
}
