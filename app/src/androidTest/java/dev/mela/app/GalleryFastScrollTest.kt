package dev.mela.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.mela.app.ui.GalleryFastScroll
import dev.mela.app.ui.GalleryScrollLabel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GalleryFastScrollTest {
    @get:Rule val compose = createComposeRule()
    @Test fun handleRevealsOnScrollDoesNotJumpOnGrabAndScrubsSparseYears() {
        compose.setContent {
            MaterialTheme {
                val grid = rememberLazyGridState()
                val headers = (0 until 24).map { it * 4 }
                val density = LocalDensity.current
                Box(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(GridCells.Adaptive(112.dp), state = grid, modifier = Modifier.fillMaxSize().testTag("test-grid"),
                        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(24) { month ->
                            item(span = { GridItemSpan(maxLineSpan) }) { Text("Month $month", Modifier.fillMaxWidth().height(40.dp)) }
                            items(3) { Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.primaryContainer)) }
                        }
                    }
                    GalleryFastScroll(grid, headers.mapIndexed { month, index -> GalleryScrollLabel(index, "Month $month · ${2026 - month / 6}", 2026 - month / 6) },
                        headers.associateWith { with(density) { 40.dp.toPx() } }, 112, Modifier.align(Alignment.CenterEnd).fillMaxHeight(), 0.dp)
                }
            }
        }
        compose.onNodeWithTag("fast-scroll-handle").assertIsNotDisplayed()
        compose.onNodeWithTag("test-grid").performTouchInput { swipeUp(durationMillis = 450) }
        compose.onNodeWithTag("fast-scroll-handle").assertIsDisplayed()
        val rail = compose.onNodeWithTag("gallery-fast-scroll")
        rail.performSemanticsAction(SemanticsActions.SetProgress) { it(.3f) }
        compose.waitForIdle()
        fun progress() = rail.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        val before = progress()
        val handleBounds = compose.onNodeWithTag("fast-scroll-handle").fetchSemanticsNode().boundsInRoot
        val railBounds = rail.fetchSemanticsNode().boundsInRoot
        val grip = handleBounds.center - railBounds.topLeft
        rail.performTouchInput { down(grip) }
        compose.onNodeWithTag("fast-scroll-date").assertIsDisplayed()
        assertEquals("Taking hold should preserve position", before, progress(), .005f)
        rail.performTouchInput { moveBy(Offset(0f, 80f), 300) }
        compose.waitForIdle()
        assertTrue("Dragging down should move continuously through the library", progress() > before)
        val firstMove = progress()
        rail.performTouchInput { moveBy(Offset(0f, 80f), 300) }
        compose.waitForIdle()
        assertEquals("Equal finger travel should produce equal scroll progress", firstMove - before, progress() - firstMove, .015f)
        compose.onNodeWithTag("fast-scroll-year-2026").assertIsDisplayed()
        val screenshot = compose.onRoot().captureToImage()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val dir = context.getExternalFilesDir("gallery-polish")!!.apply { mkdirs() }
        java.io.File(dir, "scrubber-years.png").outputStream().use {
            screenshot.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        val yearBounds = compose.onNodeWithTag("fast-scroll-year-2026").fetchSemanticsNode().boundsInRoot
        rail.performTouchInput { moveBy(Offset(0f, -height.toFloat()), 500) }
        compose.waitForIdle()
        assertEquals(0f, progress(), .005f)
        assertEquals("The year stays in place behind the scrubber", yearBounds,
            compose.onNodeWithTag("fast-scroll-year-2026").fetchSemanticsNode().boundsInRoot)
        rail.performTouchInput { up() }
        compose.onNodeWithTag("fast-scroll-date").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(2800)
        compose.onNodeWithTag("fast-scroll-handle").assertIsNotDisplayed()
    }
}
