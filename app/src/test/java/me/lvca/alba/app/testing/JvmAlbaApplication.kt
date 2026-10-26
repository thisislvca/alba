package me.lvca.alba.app.testing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.platform.ViewRootForTest
import me.lvca.alba.app.AlbaApplication

/**
 * Alba's application for Robolectric tests (see `robolectric.properties`), with two fixes for Robolectric itself:
 *
 * - It loads the native graphics runtime on the main thread before anything else can. AndroidX Startup's initializers
 *   and preview decodes touch bitmaps on background threads; when one of those loads the runtime first, its JNI setup
 *   cannot see the JDK's classes and aborts the whole test JVM.
 * - It draws dialog windows that have Compose layout pending. Compose measures a window's content during its draw
 *   pass, and Robolectric never draws windows other than the activity's, so a dialog whose content asks for another
 *   layout (any text field does) would stay "busy" and never let a Compose test go idle.
 */
class JvmAlbaApplication : AlbaApplication() {
    override fun attachBaseContext(base: Context) {
        loadNativeGraphics()
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        val canvas = Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        Looper.getMainLooper().queue.addIdleHandler {
            // Dialog and popup windows only. Drawing the activity's window here as well made software drawing replay the
            // viewer's recorded snapshot of the gallery after the gallery had left composition, which crashes; hardware
            // rendering replays the recorded display list instead.
            WindowInspector.getGlobalWindowViews()
                .filter { (it.layoutParams as? WindowManager.LayoutParams)?.type != WindowManager.LayoutParams.TYPE_BASE_APPLICATION }
                .flatMap(::composeRoots)
                .filter { it.hasPendingMeasureOrLayout }
                .forEach { it.view.draw(canvas) }
            true
        }
    }

    private fun composeRoots(view: View): List<ViewRootForTest> = when (view) {
        is ViewRootForTest -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { composeRoots(view.getChildAt(it)) }
        else -> emptyList()
    }
}
