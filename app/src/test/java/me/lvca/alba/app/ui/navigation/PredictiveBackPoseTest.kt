package me.lvca.alba.app.ui.navigation

import androidx.activity.BackEventCompat
import org.junit.Assert.assertEquals
import org.junit.Test

class PredictiveBackPoseTest {
    private val width = 1080f
    private val height = 2400f
    private val gap = 21f

    @Test fun restsAtTheStartOfTheGesture() {
        val pose = predictiveBackPose(0f, BackEventCompat.EDGE_LEFT, 0f, width, height, gap)
        assertEquals(1f, pose.scale, 0f)
        assertEquals(0f, pose.translationX, 0f)
        assertEquals(0f, pose.translationY, 0f)
        assertEquals(0f, pose.cornerFraction, 0f)
    }

    @Test fun shrinksToNinetyPercentAndMovesAwayFromTheSwipeEdge() {
        val left = predictiveBackPose(1f, BackEventCompat.EDGE_LEFT, 0f, width, height, gap)
        assertEquals(BACK_MIN_SCALE, left.scale, 1e-6f)
        // Half the freed width, less the edge gap.
        assertEquals((width * .1f) / 2f - gap, left.translationX, 1e-2f)
        val right = predictiveBackPose(1f, BackEventCompat.EDGE_RIGHT, 0f, width, height, gap)
        assertEquals(-left.translationX, right.translationX, 1e-3f)
        assertEquals(0f, predictiveBackPose(1f, BackEventCompat.EDGE_NONE, 0f, width, height, gap).translationX, 0f)
    }

    @Test fun followsTheFingerVerticallyWithinTheFreedSpace() {
        val maxY = (height * .1f) / 2f - gap
        val down = predictiveBackPose(1f, BackEventCompat.EDGE_LEFT, height / 2f, width, height, gap)
        assertEquals(maxY / 2f, down.translationY, 1e-2f)
        val up = predictiveBackPose(1f, BackEventCompat.EDGE_LEFT, -height * 3f, width, height, gap)
        assertEquals(-maxY, up.translationY, 1e-2f)
    }

    @Test fun roundsCornersEarlyAndClampsProgress() {
        assertEquals(1f, predictiveBackPose(.34f, BackEventCompat.EDGE_LEFT, 0f, width, height, gap).cornerFraction, 0f)
        assertEquals(BACK_MIN_SCALE, predictiveBackPose(2f, BackEventCompat.EDGE_LEFT, 0f, width, height, gap).scale, 1e-6f)
    }

    @Test fun neverShiftsTowardTheEdgeOnTinyWindows() {
        val pose = predictiveBackPose(1f, BackEventCompat.EDGE_LEFT, 100f, 100f, 100f, gap)
        assertEquals(0f, pose.translationX, 0f)
        assertEquals(0f, pose.translationY, 0f)
    }
}
