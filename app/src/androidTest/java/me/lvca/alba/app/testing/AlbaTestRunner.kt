package me.lvca.alba.app.testing

import android.app.KeyguardManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
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

    /**
     * Each step reads the state first and runs its shell command only when the state is wrong. The settings persist,
     * so after the first test of a run most steps cost a read instead of a shell process.
     */
    private fun prepareDevice() {
        val resolver = targetContext.contentResolver
        // An error dialog takes window focus from the app under test, failing every Espresso call and key event until
        // someone answers it. With dialogs hidden, Android kills an unresponsive process instead and logs the ANR, so
        // an ANR in the app still fails its test.
        // The key is hidden from the public API, so a read Android refuses counts as "not set".
        val errorDialogsHidden = runCatching { Settings.Global.getInt(resolver, HIDE_ERROR_DIALOGS, 0) == 1 }.getOrDefault(false)
        if (!errorDialogsHidden) shell("settings put global $HIDE_ERROR_DIALOGS 1")
        // A system process can stop responding while a freshly booted emulator settles, before that setting existed.
        // Answer its dialog with "Wait" so it does not hold focus for the whole run.
        var answered = 0
        while (answered < MAX_DIALOGS) {
            val wait = SystemWindows.find("aerr_wait", uiAutomation) ?: break
            val title = SystemWindows.find("alertTitle", uiAutomation)?.text
            Log.w(TAG, "Answering a system error dialog before the test: $title")
            wait.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            answered++
        }
        if (!targetContext.getSystemService(PowerManager::class.java).isInteractive) shell("input keyevent KEYCODE_WAKEUP")
        if (targetContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked) shell("wm dismiss-keyguard")
        if (Settings.Global.getInt(resolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0) == 0) shell("svc power stayon true")
        // Under software rendering, window and activity transitions hold input focus back from the next window for
        // seconds. Turn those off, but keep the animator scale: the app's own animations, which tests check frame by
        // frame, follow it.
        for (scale in listOf(Settings.Global.WINDOW_ANIMATION_SCALE, Settings.Global.TRANSITION_ANIMATION_SCALE)) {
            if (Settings.Global.getFloat(resolver, scale, 1f) != 0f) shell("settings put global $scale 0")
        }
        try {
            uiAutomation.waitForIdle(IDLE_MS, LONG_WAIT)
        } catch (_: TimeoutException) {
            Log.w(TAG, "The device was still busy after $LONG_WAIT ms")
        }
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
        const val HIDE_ERROR_DIALOGS = "hide_error_dialogs"
        const val MAX_DIALOGS = 3
        const val IDLE_MS = 500L
    }
}
