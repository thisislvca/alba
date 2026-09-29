package dev.mela.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

internal const val TARGET = "com.mannaworks.mela.benchmark"

internal fun MacrobenchmarkScope.waitForGallery() {
    check(device.wait(Until.hasObject(By.res("gallery-grid")), 15_000)) { "Gallery did not appear" }
    check(device.wait(Until.hasObject(By.res("media-fixture:icloud:0001")), 15_000)) { "Demo photos did not load" }
}

internal fun MacrobenchmarkScope.browseGallery() {
    waitForGallery()
    val grid = requireNotNull(device.findObject(By.res("gallery-grid")))
    grid.setGestureMargin(device.displayWidth / 5)
    repeat(3) { grid.fling(Direction.DOWN); device.waitForIdle() }
    repeat(3) { grid.fling(Direction.UP); device.waitForIdle() }
    grid.scroll(Direction.DOWN, 0.6f)
    device.waitForIdle()
    val bounds = grid.visibleBounds
    val navigationTop = device.findObject(By.res("tab-LIBRARY"))?.visibleBounds?.top
        ?.takeIf { it > bounds.top } ?: bounds.bottom
    val candidates = device.findObjects(By.res(java.util.regex.Pattern.compile("media-fixture:icloud:.*")))
    val photo = checkNotNull(candidates.firstOrNull {
        !it.resourceName.endsWith("0019") && it.visibleBounds.let { rect ->
            rect.height() > 0 && rect.centerY() > bounds.top && rect.centerY() < navigationTop
        }
    }) { "No tappable photo: grid=$bounds navigation=$navigationTop photos=${candidates.map { it.resourceName to it.visibleBounds }}" }
    val id = photo.resourceName.substringAfter("media-")
    photo.click()
    check(device.wait(Until.hasObject(By.res("viewer-image-$id")), 5_000))
    device.pressBack()
    check(device.wait(Until.gone(By.res("media-pager")), 5_000))
}
