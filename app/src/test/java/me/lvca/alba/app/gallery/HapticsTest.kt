package me.lvca.alba.app.gallery

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.DiagnosticsConsent
import me.lvca.alba.app.account.PhoneStorageState
import me.lvca.alba.app.actions.MediaWork
import me.lvca.alba.app.screenshots.ScreenshotFixtures
import me.lvca.alba.app.testing.GalleryStateActions
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.transfers.TransferState
import me.lvca.alba.app.ui.AlbaApp
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The haptic ticks Google Photos gives: toggling a photo in a selection, a favourite, and a swipe down passing its close point. */
@RunWith(AndroidJUnit4::class)
class HapticsTest {
    @get:Rule val compose = createComposeRule()

    private val haptics = mutableListOf<HapticFeedbackType>()
    private val recorder = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
    }

    private fun show(actions: GalleryStateActions) = compose.setContent {
        CompositionLocalProvider(LocalHapticFeedback provides recorder) {
            AlbaTheme(darkTheme = false) {
                AlbaApp(gallery = actions.state.value, session = ScreenshotFixtures.signedIn, transfers = TransferState(),
                    phoneStorage = PhoneStorageState(), mediaWork = MediaWork(), previewRetryVersion = 0, message = null,
                    diagnostics = DiagnosticsConsent(), actions = actions)
            }
        }
    }

    @Test fun tappingPhotosInASelectionTicksOnAndOff() {
        val actions = GalleryStateActions(ScreenshotFixtures.gallery())
        show(actions)
        compose.scrollToGalleryMedia("photo-0")
        compose.onNodeWithTag("media-photo-0").performTouchInput { longClick() }
        compose.runOnIdle { haptics.clear() }
        compose.onNodeWithTag("media-photo-1").performClick()
        compose.onNodeWithTag("media-photo-1").performClick()
        compose.runOnIdle {
            assertEquals(listOf(HapticFeedbackType.ToggleOn, HapticFeedbackType.ToggleOff), haptics)
            assertEquals(setOf("photo-0"), actions.state.value.selection)
        }
    }

    @Test fun favouritingInTheViewerTicks() {
        val gallery = ScreenshotFixtures.gallery()
        // photo-1 is not a favourite in the fixtures.
        val actions = GalleryStateActions(gallery.copy(selectedMedia = gallery.items[1]))
        show(actions)
        compose.onNodeWithTag("viewer-favorite").performClick()
        compose.runOnIdle { assertEquals(listOf(HapticFeedbackType.ToggleOn), haptics) }
    }

    @Test fun swipingDownTicksOnceAsItPassesTheClosePoint() {
        val gallery = ScreenshotFixtures.gallery()
        val actions = GalleryStateActions(gallery.copy(selectedMedia = gallery.items[1]))
        show(actions)
        val pager = compose.onNodeWithTag("media-pager")
        pager.performTouchInput {
            down(center)
            moveBy(Offset(0f, viewConfiguration.touchSlop + 1f))
            // Short of the close point: no tick.
            moveBy(Offset(0f, 12.dp.toPx()))
        }
        compose.runOnIdle { assertEquals(emptyList<HapticFeedbackType>(), haptics) }
        pager.performTouchInput {
            moveBy(Offset(0f, 40.dp.toPx()))
            moveBy(Offset(0f, 20.dp.toPx()))
        }
        compose.runOnIdle { assertEquals(listOf(HapticFeedbackType.GestureThresholdActivate), haptics) }
        pager.performTouchInput { up() }
    }
}
