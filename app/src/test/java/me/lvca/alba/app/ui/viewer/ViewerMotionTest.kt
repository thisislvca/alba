package me.lvca.alba.app.ui.viewer

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerMotionTest {
    private val fullEffect = 60f

    @Test fun dragsAreClaimedByDirection() {
        assertEquals(ViewerDrag.DISMISS, classifyViewerDrag(Offset(0f, 20f), detailsOpen = false))
        assertEquals("Within 45° of down still dismisses", ViewerDrag.DISMISS, classifyViewerDrag(Offset(-19f, 20f), detailsOpen = false))
        assertNull("Flatter than 45° belongs to the pager", classifyViewerDrag(Offset(21f, 20f), detailsOpen = false))
        assertEquals(ViewerDrag.DETAILS, classifyViewerDrag(Offset(5f, -20f), detailsOpen = false))
        assertNull("Details only start within 30° of straight up", classifyViewerDrag(Offset(15f, -20f), detailsOpen = false))
        assertEquals("With details open any vertical drag moves the sheet", ViewerDrag.DETAILS, classifyViewerDrag(Offset(0f, 20f), detailsOpen = true))
        assertNull(classifyViewerDrag(Offset(30f, 20f), detailsOpen = true))
    }

    @Test fun dismissReachesFullEffectAtSixtyDpInAnyDirection() {
        assertEquals(ViewerPose.Rest, dismissPose(Offset.Zero, fullEffect))
        val half = dismissPose(Offset(18f, 24f), fullEffect)
        assertEquals(.925f, half.scale, 1e-4f)
        assertEquals(.5f, half.backdrop, 1e-4f)
        assertEquals("The photo follows the finger in X too", Offset(18f, 24f), half.translation)
        val beyond = dismissPose(Offset(0f, 200f), fullEffect)
        assertEquals(ViewerSpec.DISMISS_MIN_SCALE, beyond.scale, 1e-4f)
        assertEquals(0f, beyond.backdrop, 1e-4f)
    }

    @Test fun releaseDismissesPastThirtyDpWithoutAVelocityTest() {
        assertFalse(shouldDismiss(Offset(0f, 29f), fullEffect))
        assertTrue(shouldDismiss(Offset(0f, 31f), fullEffect))
        assertTrue("Diagonal travel counts by distance", shouldDismiss(Offset(24f, 24f), fullEffect))
    }

    @Test fun detailsFollowTheReleaseDirectionAndOtherwiseThePosition() {
        assertTrue(detailsOpenAfterRelease(.1f, -50f))
        assertFalse(detailsOpenAfterRelease(.9f, 50f))
        assertTrue("Nearly still: more than half open stays open", detailsOpenAfterRelease(.6f, 5f))
        assertFalse(detailsOpenAfterRelease(.4f, -5f))
    }

    @Test fun detailsLeaveASixteenByNineBandInPortraitAndTwoThirdsInLandscape() {
        assertEquals(2400f - 100f - 1080f * 9f / 16f, detailsSheetHeight(1080f, 2400f, 100f), .5f)
        assertEquals(1080f * 2f / 3f, detailsSheetHeight(2400f, 1080f, 100f), .5f)
    }

    @Test fun predictiveBackShrinksTowardSixtyFivePercentAndFollowsTheTouch() {
        val start = predictiveBackPose(0f, 1f, 0f, 1000f)
        assertEquals(1f, start.scale, 1e-4f)
        assertEquals(1f, start.backdrop, 1e-4f)
        val end = predictiveBackPose(1f, -1f, -32f, 1000f)
        assertEquals(.65f, end.scale, 1e-4f)
        assertEquals(.1f, end.backdrop, 1e-4f)
        assertEquals("A right-edge swipe moves the photo left", -1000f * .35f * .35f, end.translation.x, 1e-3f)
        assertEquals(-16f, end.translation.y, 1e-3f)
    }

    @Test fun handedOffPansSettleOnTheNearestPageOrFollowAFling() {
        assertEquals(3, handoffTargetPage(3, 3.3f, 0f, 1000f, 10))
        assertEquals(4, handoffTargetPage(3, 3.6f, 0f, 1000f, 10))
        assertEquals(4, handoffTargetPage(3, 3.1f, -1200f, 1000f, 10))
        assertEquals("A fling against the drag stays", 3, handoffTargetPage(3, 3.1f, 1200f, 1000f, 10))
        assertEquals(2, handoffTargetPage(3, 2.4f, 0f, 1000f, 10))
        assertEquals(9, handoffTargetPage(9, 9.7f, 0f, 1000f, 10))
    }
}
