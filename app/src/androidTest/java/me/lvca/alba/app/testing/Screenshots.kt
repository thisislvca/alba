package me.lvca.alba.app.testing

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import java.io.File
import java.io.OutputStream

/** Saves whole-screen captures, including system windows and dialogs that Compose's own capture cannot see. */
internal object Screenshots {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /**
     * Opens [name] in the run's test output. The test storage service keeps it outside the app, so it survives the
     * orchestrator clearing app data, and AGP copies it to `build/outputs/<device kind>_android_test_additional_output`.
     */
    fun testOutput(name: String): OutputStream = TestStorage().openOutputFile(name)

    /** The app's external files directory [name], which stays on the device for tools to pull. */
    fun appFiles(name: String): File = requireNotNull(instrumentation.targetContext.getExternalFilesDir(name))

    /** Saves the screen as [name].png in the run's test output. */
    fun capture(name: String) = testOutput("$name.png").use(::writeScreen)

    /** Saves the screen as [name].png in [directory]. */
    fun capture(name: String, directory: File) {
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use(::writeScreen)
    }

    private fun writeScreen(output: OutputStream) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        } finally {
            bitmap.recycle()
        }
    }
}
