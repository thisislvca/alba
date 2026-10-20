package me.lvca.alba.app.testing

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Saves whole-screen captures, including system windows and dialogs that Compose's own capture cannot see. */
internal object Screenshots {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** The run's additional test output, which AGP pulls from the device; app files under [fallback] otherwise. */
    fun testOutput(fallback: String = "test-output"): File =
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: File(instrumentation.targetContext.getExternalFilesDir(null), fallback)

    /** The app's external files directory [name], which stays on the device for tools to pull. */
    fun appFiles(name: String): File = requireNotNull(instrumentation.targetContext.getExternalFilesDir(name))

    /** Saves the screen as [name].png in [directory]. */
    fun capture(name: String, directory: File = testOutput()) {
        directory.mkdirs()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }
}
