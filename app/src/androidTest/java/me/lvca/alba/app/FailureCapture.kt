package me.lvca.alba.app

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Saves a screenshot and the semantics tree when a test fails, so CI-only failures can be diagnosed.
 * Declare it with a higher rule order than the compose rule so it runs while the activity is still open.
 */
class FailureCapture(private val compose: () -> ComposeTestRule) : TestWatcher() {
    override fun failed(e: Throwable, description: Description) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: File(instrumentation.targetContext.getExternalFilesDir(null), "failures")
        directory.mkdirs()
        val name = "failure-${description.testClass.simpleName}-${description.methodName}"
        runCatching {
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        runCatching {
            File(directory, "$name-semantics.txt").writeText(compose().onRoot(useUnmergedTree = true).printToString())
        }
    }
}
