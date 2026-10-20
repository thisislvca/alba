package me.lvca.alba.app.viewer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import me.lvca.alba.engine.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.LONG_WAIT
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.viewer.VideoPlayer

class VideoPlayerTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createComposeRule()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }
    private val app get() = ApplicationProvider.getApplicationContext<AlbaApplication>()
    private fun text(id: Int) = app.getString(id)

    @Test fun failedVideoCanRetryInPlace() {
        val broken = java.util.concurrent.atomic.AtomicBoolean(true)
        val bytes = app.assets.open("fixture_video.mp4").use { it.readBytes() }
        compose.setContent { AlbaTheme(darkTheme = true) {
            VideoPlayer("retry-clip", reader = { _, position, count ->
                if (broken.get()) throw java.io.IOException("Connection interrupted")
                val byteCount = if (count < 0) bytes.size - position.toInt() else minOf(count.toInt(), bytes.size - position.toInt())
                object : me.lvca.alba.engine.source.MediaRead {
                    override val length = byteCount.toLong()
                    override val input = java.io.ByteArrayInputStream(bytes, position.toInt(), byteCount)
                    override fun close() = input.close()
                }
            }, modifier = Modifier.fillMaxSize())
        } }
        compose.waitUntil(LONG_WAIT) { compose.onAllNodesWithTag("retry-video").fetchSemanticsNodes().isNotEmpty() }
        broken.set(false)
        compose.onNodeWithTag("retry-video").performClick()
        compose.waitUntil(LONG_WAIT) { compose.onAllNodes(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, text(R.string.ready))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("retry-video").assertDoesNotExist()
    }
}
