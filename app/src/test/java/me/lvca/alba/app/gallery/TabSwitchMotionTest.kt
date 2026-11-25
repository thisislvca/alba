package me.lvca.alba.app.gallery

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.screenshots.ScreenshotFixtures
import me.lvca.alba.app.testing.setAlbaContent
import me.lvca.alba.app.ui.designsystem.theme.AlbaLightColorScheme
import me.lvca.alba.app.ui.designsystem.theme.floating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Switching destinations with the floating navigation is one motion: the pill travels once to the tapped destination,
 * while the page (header and screen) fades out and the new one fades in, sampled frame by frame on the paused clock.
 * A frame's ink is how far the page above the navigation is from a blank page, so a fade through is one fall to blank
 * and one rise.
 */
@RunWith(AndroidJUnit4::class)
class TabSwitchMotionTest {
    @get:Rule val compose = createComposeRule()

    private data class Frame(val ink: Double, val pillStart: Int, val pillEnd: Int)

    @Test fun switchingDestinationsFadesThroughOnceWhileThePillTravelsOnce() {
        compose.setAlbaContent(ScreenshotFixtures.gallery(), ScreenshotFixtures.signedIn)
        val start = current()
        val frames = listOf(start) + record(FRAMES) { tap("tab-COLLECTIONS") }
        val description = frames.joinToString("\n")
        assertSingleFadeThrough(frames.map { it.ink }, description)
        assertTrue("the fade out takes about 90 ms:\n$description", frames.indexOfFirst { it.ink < BLANK } <= FADE_OUT_FRAMES)
        assertTrue("Collections shows:\n$description", frames.last().ink > BLANK)
        assertEquals("the fade in has ended:\n$description", frames[frames.size - 2].ink, frames.last().ink, EPSILON)

        // The pill sets off in the tap's first frames and travels one way, without a jump, to Collections.
        // Frame 1 composes the tap and frame 2 starts the spring, as the fade starts: frame 3 is its first step.
        assertTrue("the pill moves at once:\n$description", frames[3].pillStart > start.pillStart)
        val settled = current()
        assertEquals("the pill is still travelling:\n$description", settled, frames.last())
        assertTrue("the pill leaves Photos:\n$description", settled.pillStart > start.pillEnd - start.pillStart)
        frames.indices.drop(1).forEach {
            val (previous, frame) = frames[it - 1] to frames[it]
            assertTrue("the pill turns back at frame $it:\n$description", frame.pillStart >= previous.pillStart - PILL_SLACK)
            assertTrue("the pill turns back at frame $it:\n$description", frame.pillEnd >= previous.pillEnd - PILL_SLACK)
            assertTrue("the pill jumps at frame $it:\n$description", frame.pillStart - previous.pillStart < MAX_PILL_STEP)
        }
    }

    @Test fun tappingBackDuringTheFadeOutReturnsFromWhereItWas() {
        compose.setAlbaContent(ScreenshotFixtures.gallery(), ScreenshotFixtures.signedIn)
        val full = current()
        val before = record(INTERRUPT_AFTER) { tap("tab-COLLECTIONS") }.last()
        val frames = listOf(before) + record(FRAMES) { tap("tab-LIBRARY") }
        val description = frames.joinToString("\n")
        compose.onAllNodesWithTag("collection-albums").assertCountEquals(0)

        // Photos comes back from the opacity it had reached: no flash to blank or to full, and no second fade out.
        assertTrue("the fade had begun:\n$description", before.ink > BLANK && before.ink < full.ink - EPSILON)
        frames.indices.drop(1).forEach {
            assertTrue("Photos fades out again at frame $it:\n$description", frames[it].ink >= frames[it - 1].ink - EPSILON)
        }
        assertEquals("Photos is back in full:\n$description", full.ink, frames.last().ink, EPSILON)

        // The pill turns round from where it was, without snapping to either end.
        assertTrue("the pill had left Photos:\n$description", before.pillStart > full.pillStart)
        frames.indices.drop(1).forEach {
            val (previous, frame) = frames[it - 1] to frames[it]
            assertTrue("the pill turns again at frame $it:\n$description", frame.pillStart <= previous.pillStart + PILL_SLACK)
            assertTrue("the pill jumps at frame $it:\n$description", previous.pillStart - frame.pillStart < MAX_PILL_STEP)
        }
        assertEquals("the pill is back on Photos:\n$description", full.pillStart.toFloat(), frames.last().pillStart.toFloat(), PILL_SLACK.toFloat())
    }

    @Test fun tappingBackDuringTheFadeInFadesThroughFromWhereItWas() {
        compose.setAlbaContent(ScreenshotFixtures.gallery(), ScreenshotFixtures.signedIn)
        current()
        compose.mainClock.autoAdvance = false
        val going = mutableListOf<Frame>()
        try {
            tap("tab-COLLECTIONS")
            // Through the blank page and a few frames into Collections' fade in.
            while (going.size < FRAMES && (going.none { it.ink < BLANK } || going.takeLastWhile { it.ink > BLANK }.size < 3)) {
                compose.mainClock.advanceTimeByFrame()
                going += sample()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        val before = going.last()
        val frames = listOf(before) + record(FRAMES) { tap("tab-LIBRARY") }
        val description = (going + frames.drop(1)).joinToString("\n")

        assertTrue("Collections had begun to fade in:\n$description", before.ink > BLANK && going.size < FRAMES)
        assertSingleFadeThrough(frames.map { it.ink }, description)
        // Photos composes afresh, so its page may differ slightly from before; it is in full once the fade has ended.
        assertEquals("the fade in has ended:\n$description", frames[frames.size - 2].ink, frames.last().ink, EPSILON)
        assertTrue("Photos shows:\n$description", frames.last().ink > before.ink)
        compose.onNodeWithTag("gallery-grid").fetchSemanticsNode()
    }

    @Test fun reducedMotionSwitchesAtOnce() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.setAlbaContent(ScreenshotFixtures.gallery(), ScreenshotFixtures.signedIn)
        val before = current()
        val frames = record(SETTLE_FRAMES) { tap("tab-COLLECTIONS") }
        val final = frames.last()
        val description = (listOf(before) + frames).joinToString("\n")

        // Animations are off, so Compose could run no fade or spring: the pill lands in the tap's first frame, and
        // Collections is in full by the next one, never passing through a faded or blank page.
        assertTrue("Collections shows:\n$description", abs(final.ink - before.ink) > EPSILON)
        frames.forEach { assertEquals("the pill travels:\n$description", final.pillStart, it.pillStart) }
        frames.drop(1).forEach { assertEquals("Collections fades in:\n$description", final.ink, it.ink, EPSILON) }
        assertTrue("the page fades:\n$description", frames.first().ink >= final.ink - EPSILON)
    }

    /** One fall to a blank page and one rise from it, with the page blank only briefly between them. */
    private fun assertSingleFadeThrough(ink: List<Double>, description: String) {
        val blank = ink.indices.minBy { ink[it] }
        assertTrue("the page never goes blank:\n$description", ink[blank] < BLANK)
        (1..blank).forEach { assertTrue("the fade out turns back at frame $it:\n$description", ink[it] <= ink[it - 1] + EPSILON) }
        (blank + 1 until ink.size).forEach {
            assertTrue("the fade in turns back at frame $it:\n$description", ink[it] >= ink[it - 1] - EPSILON)
        }
        assertTrue("the page stays blank too long:\n$description", ink.count { it < BLANK } <= MAX_BLANK_FRAMES)
    }

    /**
     * Taps a destination through its click action, so the tap lands before the next frame's animations as a touch does
     * on a device, where input comes first in a frame.
     */
    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun current(): Frame {
        compose.waitForIdle()
        return sample()
    }

    /** Runs [action] on the paused clock, then samples each of the next [count] frames. */
    private fun record(count: Int, action: () -> Unit): List<Frame> {
        compose.mainClock.autoAdvance = false
        try {
            action()
            return List(count) {
                compose.mainClock.advanceTimeByFrame()
                sample()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun sample(): Frame {
        val image = compose.onRoot().captureToImage().toPixelMap()
        val capsule = compose.onNodeWithTag("gallery-destination-capsule").fetchSemanticsNode().boundsInRoot
        val page = AlbaLightColorScheme.background
        val pageBottom = capsule.top.toInt() - with(compose.density) { 8.dp.roundToPx() }
        var sum = 0.0
        var count = 0
        for (y in 0 until pageBottom step SAMPLE_STEP) {
            for (x in 0 until image.width step SAMPLE_STEP) {
                sum += image[x, y].distanceTo(page)
                count++
            }
        }
        // The pill is the longest run of its colour along a row inside it, above the destinations' glyphs.
        val active = AlbaLightColorScheme.floating.active
        val row = (capsule.top + with(compose.density) { 12.dp.toPx() }).toInt()
        var pill = -1 to -2
        var start = -1
        for (x in capsule.left.toInt()..capsule.right.toInt()) {
            val on = x < capsule.right.toInt() && image[x, row].distanceTo(active) < PILL_COLOUR
            if (on && start < 0) start = x
            if (!on && start >= 0) {
                if (x - start > pill.second - pill.first + 1) pill = start to x - 1
                start = -1
            }
        }
        val left = capsule.left.toInt()
        return Frame(sum / count, pill.first - left, pill.second - left)
    }

    private fun Color.distanceTo(other: Color): Double =
        (abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue)).toDouble()

    private companion object {
        const val FRAMES = 32
        const val SETTLE_FRAMES = 6
        const val INTERRUPT_AFTER = 3
        const val SAMPLE_STEP = 4
        const val BLANK = .005
        const val EPSILON = .002
        const val MAX_BLANK_FRAMES = 3
        const val FADE_OUT_FRAMES = 8
        const val PILL_COLOUR = .03
        const val PILL_SLACK = 2
        const val MAX_PILL_STEP = 120
    }
}
