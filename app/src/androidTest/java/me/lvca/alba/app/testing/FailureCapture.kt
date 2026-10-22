package me.lvca.alba.app.testing

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Saves a screenshot and the semantics tree when a test fails, so CI-only failures can be diagnosed.
 * Declare it with a higher rule order than the compose rule so it runs while the activity is still open.
 */
class FailureCapture(private val compose: () -> ComposeTestRule) : TestWatcher() {
    override fun failed(e: Throwable, description: Description) {
        val name = "failure-${description.testClass.simpleName}-${description.methodName}"
        runCatching { Screenshots.capture(name) }
        runCatching {
            val semantics = compose().onRoot(useUnmergedTree = true).printToString()
            Screenshots.testOutput("$name-semantics.txt").use { it.write(semantics.toByteArray()) }
        }
    }
}
