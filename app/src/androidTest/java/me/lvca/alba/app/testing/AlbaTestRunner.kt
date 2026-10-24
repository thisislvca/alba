package me.lvca.alba.app.testing

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.runner.AndroidJUnitRunner
import java.util.concurrent.TimeoutException

/**
 * Puts the device in a known state before tests start: awake, unlocked, settled, without window transitions, and with
 * no system dialog holding window focus. The test orchestrator starts a new instrumentation for every test, so this
 * runs before each one. The settings persist, which suits the disposable emulators device tests run on.
 */
class AlbaTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        // Artwork captures run only when a run selects them by annotation. The orchestrator lists the tests through
        // this runner as well, so ordinary runs never schedule them.
        val capture = ScreenshotCapture::class.java.name
        if (arguments.getString(ANNOTATION) != capture) {
            arguments.putString(NOT_ANNOTATION, listOfNotNull(arguments.getString(NOT_ANNOTATION), capture).joinToString(","))
        }
        super.onCreate(arguments)
    }

    override fun onStart() {
        prepareDevice()
        super.onStart()
    }

    private fun prepareDevice() {
        // An error dialog takes window focus from the app under test, failing every Espresso call and key event until
        // someone answers it. With dialogs hidden, Android kills an unresponsive process instead and logs the ANR, so
        // an ANR in the app still fails its test.
        shell("settings put global hide_error_dialogs 1")
        // A system process can stop responding while a freshly booted emulator settles, before that setting existed.
        // Answer its dialog with "Wait" so it does not hold focus for the whole run.
        repeat(MAX_DIALOGS) {
            val wait = SystemWindows.find("aerr_wait", uiAutomation) ?: return@repeat
            val title = SystemWindows.find("alertTitle", uiAutomation)?.text
            Log.w(TAG, "Answering a system error dialog before the test: $title")
            wait.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("svc power stayon true")
        // Under software rendering, window and activity transitions hold input focus back from the next window for
        // seconds. Turn those off, but keep the animator scale: the app's own animations, which tests check frame by
        // frame, follow it.
        shell("settings put global ${Settings.Global.WINDOW_ANIMATION_SCALE} 0")
        shell("settings put global ${Settings.Global.TRANSITION_ANIMATION_SCALE} 0")
        try {
            uiAutomation.waitForIdle(IDLE_MS, LONG_WAIT)
        } catch (_: TimeoutException) {
            Log.w(TAG, "The device was still busy after $LONG_WAIT ms")
        }
        val resolver = targetContext.contentResolver
        val scales = listOf(Settings.Global.WINDOW_ANIMATION_SCALE, Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.ANIMATOR_DURATION_SCALE)
            .joinToString { "$it=${Settings.Global.getFloat(resolver, it, 1f)}" }
        Log.i(TAG, "Device ready; $scales")
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private companion object {
        const val TAG = "AlbaTestRunner"
        const val ANNOTATION = "annotation"
        const val NOT_ANNOTATION = "notAnnotation"
        const val MAX_DIALOGS = 3
        const val IDLE_MS = 500L
    }
}
