package me.lvca.alba.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Frame cost of the gallery's heavier interactions on a release-like build. The traces show main-thread work per
 * frame; debug builds exaggerate it several times over.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class GalleryInteractionBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val metrics = listOf(FrameTimingMetric(), TraceSectionMetric("Choreographer#doFrame%", TraceSectionMetric.Mode.Max))

    /** Pinching between grid levels, including the frames right after each commit. */
    @Test fun pinchBetweenGridLevels() = benchmark.measureRepeated(
        packageName = TARGET, metrics = metrics, iterations = 5, compilationMode = CompilationMode.Partial(),
        setupBlock = { pressHome(); startActivityAndWait(); waitForPhotos() },
    ) {
        val grid = requireNotNull(device.findObject(By.res("gallery-grid")))
        grid.setGestureMargin(device.displayWidth / 5)
        repeat(2) {
            grid.pinchOpen(.6f, PINCH_SPEED); device.waitForIdle(); Thread.sleep(SETTLE_MILLIS)
            grid.pinchClose(.6f, PINCH_SPEED); device.waitForIdle(); Thread.sleep(SETTLE_MILLIS)
        }
    }

    /** The first switch to Collections after launch, the costliest tab change, and back. */
    @Test fun firstTabSwitch() = benchmark.measureRepeated(
        packageName = TARGET, metrics = metrics, iterations = 5, compilationMode = CompilationMode.Partial(),
        setupBlock = { killProcess(); startActivityAndWait(); waitForPhotos(); Thread.sleep(SETTLE_MILLIS) },
    ) {
        requireNotNull(device.findObject(By.res("tab-COLLECTIONS"))).click(); device.waitForIdle(); Thread.sleep(SETTLE_MILLIS)
        requireNotNull(device.findObject(By.res("tab-LIBRARY"))).click(); device.waitForIdle(); Thread.sleep(SETTLE_MILLIS)
    }

    private fun MacrobenchmarkScope.waitForPhotos() {
        check(device.wait(Until.hasObject(By.res(Pattern.compile("media-.*"))), PHOTOS_TIMEOUT)) { "No photos appeared" }
    }

    private companion object {
        const val PINCH_SPEED = 1_500
        const val SETTLE_MILLIS = 800L
        const val PHOTOS_TIMEOUT = 30_000L
    }
}
