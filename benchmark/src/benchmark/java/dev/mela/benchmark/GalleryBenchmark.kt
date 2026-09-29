package dev.mela.benchmark

import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun coldStartupWithoutCompilation() = startup(CompilationMode.None())
    @Test fun coldStartupWithBaselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) = benchmark.measureRepeated(
        packageName = TARGET, metrics = listOf(StartupTimingMetric()), iterations = 10,
        compilationMode = mode, startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
    ) { startActivityAndWait(); waitForGallery() }

    @Test fun galleryScrollAndViewerWithBaselineProfile() = benchmark.measureRepeated(
        packageName = TARGET, metrics = listOf(FrameTimingMetric()), iterations = 5,
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        setupBlock = { pressHome(); startActivityAndWait(); waitForGallery() },
    ) { browseGallery() }
}
