package me.lvca.alba.app.testing

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry

/** Windows outside the app, such as Android's confirmation and error dialogs, read through accessibility. */
internal object SystemWindows {
    private const val POLL_MS = 100L

    /** The node with Android's view id [id] in any window on screen, not only the one that has input focus. */
    fun find(id: String, automation: UiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation): AccessibilityNodeInfo? {
        val info = automation.serviceInfo
        if (info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
            automation.serviceInfo = info.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        }
        return automation.windows.asSequence().mapNotNull { it.root }
            .firstNotNullOfOrNull { it.findAccessibilityNodeInfosByViewId("android:id/$id").firstOrNull() }
    }

    /** Waits up to [timeoutMs] for [find] to return the node; platform windows are not driven by the Compose clock. */
    fun await(id: String, timeoutMs: Long): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            find(id)?.let { return it }
            if (SystemClock.uptimeMillis() >= deadline) return null
            SystemClock.sleep(POLL_MS)
        }
    }
}
