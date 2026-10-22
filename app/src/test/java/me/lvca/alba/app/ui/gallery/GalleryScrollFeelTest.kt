package me.lvca.alba.app.ui.gallery

import org.junit.Assert.*
import org.junit.Test

class GalleryScrollFeelTest {
    @Test fun dragAutoscrollRampsToItsCapInsideTheEdgeBands() {
        val speed = { y: Float -> dragAutoscrollSpeed(y, top = 0f, bottom = 1000f, edge = 56f, maxSpeed = 600f) }
        assertEquals(0f, speed(500f), 0f)
        assertEquals(0f, speed(56f), 0f)
        assertEquals(-300f, speed(28f), 1e-3f)
        assertEquals(-600f, speed(-40f), 1e-3f)
        assertEquals(300f, speed(972f), 1e-3f)
        assertEquals(600f, speed(1200f), 1e-3f)
    }

    @Test fun headerStartsHidingOnlyAboveItsSpeedGateThenFollowsOneToOne() {
        val gate = HeaderScrollGate(revealPxPerSecond = 400f, hidePxPerSecond = 80f)
        var t = 0L
        fun step(delta: Float, offset: Float) = gate.offsetAfter(delta, t.also { t += 16_000_000L }, offset, -200f)
        // 1px per 16ms is about 60px/s: below the hide gate, so a shown header stays put.
        assertEquals(0f, step(-1f, 0f), 0f)
        assertEquals(0f, step(-1f, 0f), 0f)
        // 10px per frame is about 600px/s.
        val moved = step(-10f, 0f)
        assertEquals(-10f, moved, 1e-3f)
        // Once partly hidden it follows any delta, even a slow reversal.
        assertEquals(-9f, step(1f, moved), 1e-3f)
        assertEquals(-200f, step(-500f, -9f), 0f)
    }

    @Test fun hiddenHeaderNeedsAFastFlickToReveal() {
        val gate = HeaderScrollGate(revealPxPerSecond = 400f, hidePxPerSecond = 80f)
        var t = 0L
        fun step(delta: Float, offset: Float) = gate.offsetAfter(delta, t.also { t += 16_000_000L }, offset, -200f)
        // About 300px/s back up does not bring the header back.
        assertEquals(-200f, step(5f, -200f), 0f)
        assertEquals(-200f, step(5f, -200f), 0f)
        // About 1200px/s does.
        assertEquals(-180f, step(20f, -200f), 1e-3f)
        assertEquals(0f, gate.settleTarget(-180f, -200f))
        assertNull(gate.settleTarget(0f, -200f))
        assertNull(gate.settleTarget(-200f, -200f))
    }

    @Test fun partlyHiddenHeaderSettlesTheWayItWasMoving() {
        val gate = HeaderScrollGate(revealPxPerSecond = 400f, hidePxPerSecond = 80f)
        val offset = gate.offsetAfter(-30f, 16_000_000L, 0f, -200f)
        assertEquals(-200f, gate.settleTarget(offset, -200f))
    }

    @Test fun everyMonthGetsAnEqualShareOfTheScrubber() {
        // Four months of very different lengths; the grid can scroll to 1000px.
        val buckets = MonthBuckets(listOf(0f, 100f, 900f, 950f), maxScroll = 1000f)
        assertEquals(0, buckets.monthAt(0f))
        assertEquals(1, buckets.monthAt(.3f))
        assertEquals(2, buckets.monthAt(.5f))
        assertEquals(3, buckets.monthAt(1f))
        assertEquals(.25f, buckets.fractionOf(1), 0f)
        assertEquals(.25f, buckets.fractionAt(100f), 1e-5f)
        assertEquals(.125f, buckets.fractionAt(50f), 1e-5f)
        assertEquals(.5f, buckets.fractionAt(900f), 1e-5f)
        assertEquals(1f, buckets.fractionAt(1000f), 0f)
    }

    @Test fun monthsPastTheEndShareTheLastStretch() {
        val buckets = MonthBuckets(listOf(50f, 400f, 1200f), maxScroll = 500f)
        assertEquals(0f, buckets.fractionAt(20f), 0f)
        assertEquals(1f / 3f, buckets.fractionAt(400f), 1e-5f)
        assertTrue(buckets.fractionAt(450f) in (1f / 3f)..1f)
        assertEquals(0f, MonthBuckets(emptyList(), 500f).fractionAt(100f), 0f)
    }
}
