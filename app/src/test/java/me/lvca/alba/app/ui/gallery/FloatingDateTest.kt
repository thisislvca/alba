package me.lvca.alba.app.ui.gallery

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

/**
 * The floating date, on the test clock: like Google Photos it shows while the grid scrolls, stays while a finger holds
 * it, hides about a second after scrolling stops, and never shows over a tab switch.
 */
@RunWith(AndroidJUnit4::class)
class FloatingDateTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var grid: LazyGridState
    private lateinit var scope: CoroutineScope
    private var switching by mutableStateOf(false)
    private var scrubbing by mutableStateOf(false)
    private val date get() = compose.onNodeWithTag("floating-date-button")

    @After fun restoreAnimations() {
        Settings.Global.putFloat(ApplicationProvider.getApplicationContext<Context>().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    private fun show() {
        compose.setContent {
            AlbaTheme(darkTheme = false) {
                grid = rememberLazyGridState()
                scope = rememberCoroutineScope()
                val scrolled by rememberRecentlyScrolled(grid) { scrubbing }
                Box(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(GridCells.Fixed(3), state = grid, modifier = Modifier.fillMaxSize()) {
                        items(300) { Box(Modifier.height(120.dp)) }
                    }
                    FloatingGridDate(YearMonth.of(2026, 11), dates = {}, recentlyScrolled = { scrolled }, switching = { switching })
                }
            }
        }
        compose.mainClock.autoAdvance = false
    }

    /** Scrolls the grid and keeps the scroll going, as a finger resting on it does, until the returned signal completes. */
    private fun holdScroll(): CompletableDeferred<Unit> {
        val release = CompletableDeferred<Unit>()
        compose.runOnIdle { scope.launch { grid.scroll { scrollBy(600f); release.await() } } }
        compose.mainClock.advanceTimeByFrame()
        return release
    }

    private fun stopScrolling(release: CompletableDeferred<Unit>) {
        release.complete(Unit)
        compose.mainClock.advanceTimeByFrame()
    }

    @Test fun theDateShowsWhileScrollingAndHidesAboutASecondAfterItStops() {
        show()
        date.assertDoesNotExist()
        // A position set by code is not a scroll: it shows nothing.
        compose.runOnIdle { grid.requestScrollToItem(30) }
        compose.mainClock.advanceTimeBy(100)
        date.assertDoesNotExist()
        compose.runOnIdle { scope.launch { grid.scrollToItem(60) } }
        compose.mainClock.advanceTimeBy(100)
        date.assertDoesNotExist()

        val release = holdScroll()
        compose.mainClock.advanceTimeBy(100)
        date.assertIsDisplayed()
        // However long the finger rests on the grid, the date stays.
        compose.mainClock.advanceTimeBy(15_000)
        date.assertIsDisplayed()

        stopScrolling(release)
        compose.mainClock.advanceTimeBy(800)
        date.assertIsDisplayed()
        // About a second after scrolling stops it has faded out, not 15 seconds later.
        compose.mainClock.advanceTimeBy(500)
        date.assertDoesNotExist()
    }

    @Test fun holdingTheScrubberKeepsItAndLettingGoHidesIt() {
        show()
        // The scrubber jumps by code, month by month, so the date follows the held thumb rather than the scrolls.
        compose.runOnIdle { scrubbing = true; Snapshot.sendApplyNotifications() }
        compose.runOnIdle { scope.launch { grid.scrollToItem(90) } }
        compose.mainClock.advanceTimeBy(5_000)
        date.assertIsDisplayed()
        compose.runOnIdle { scrubbing = false; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(800)
        date.assertIsDisplayed()
        compose.mainClock.advanceTimeBy(500)
        date.assertDoesNotExist()
    }

    @Test fun scrollingAgainBeforeItHidesKeepsIt() {
        show()
        stopScrolling(holdScroll())
        compose.mainClock.advanceTimeBy(700)
        stopScrolling(holdScroll())
        compose.mainClock.advanceTimeBy(700)
        date.assertIsDisplayed()
        compose.mainClock.advanceTimeBy(600)
        date.assertDoesNotExist()
    }

    @Test fun withReducedMotionItGoesWithoutAFade() {
        Settings.Global.putFloat(ApplicationProvider.getApplicationContext<Context>().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        show()
        stopScrolling(holdScroll())
        compose.mainClock.advanceTimeBy(GalleryFeel.DATE_PILL_HOLD_MILLIS - 100)
        date.assertIsDisplayed()
        // The hold ends and the next frame has no date left to fade.
        compose.mainClock.advanceTimeBy(100 + 2 * FRAME_MILLIS)
        date.assertDoesNotExist()
    }

    @Test fun aTabSwitchRemovesItAtOnce() {
        show()
        val release = holdScroll()
        compose.mainClock.advanceTimeBy(100)
        date.assertIsDisplayed()
        switching = true
        compose.mainClock.advanceTimeByFrame()
        // Gone in the switch's first frame, though the grid is still scrolling: nothing floats over the blank page.
        date.assertDoesNotExist()
        switching = false
        stopScrolling(release)
    }

    @Test fun aHeaderFadesAsItSlidesUnderTheStatusBar() {
        // Below the status bar in full, gone once wholly under it, and in between in proportion.
        assertEquals(1f, statusBarFade(top = 200f, height = 40f, statusBarBottom = 100f), 0f)
        assertEquals(1f, statusBarFade(top = 100f, height = 40f, statusBarBottom = 100f), 0f)
        assertEquals(.5f, statusBarFade(top = 80f, height = 40f, statusBarBottom = 100f), 1e-6f)
        assertEquals(0f, statusBarFade(top = 60f, height = 40f, statusBarBottom = 100f), 0f)
        assertEquals(0f, statusBarFade(top = -300f, height = 40f, statusBarBottom = 100f), 0f)
        // Before its first placement it draws normally.
        assertEquals(1f, statusBarFade(top = Float.NaN, height = 0f, statusBarBottom = 100f), 0f)
    }

    private companion object {
        const val FRAME_MILLIS = 16L
    }
}
