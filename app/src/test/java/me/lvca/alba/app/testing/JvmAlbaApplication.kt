package me.lvca.alba.app.testing

import android.content.Context
import me.lvca.alba.app.AlbaApplication

/**
 * Alba's application for Robolectric tests (see `robolectric.properties`). It loads Robolectric's native graphics
 * runtime on the main thread before anything else can: AndroidX Startup's initializers (EmojiCompat) touch fonts and
 * bitmaps on background threads, and when one of those threads loads the runtime first, its JNI setup cannot see the
 * JDK's classes and aborts the whole test JVM.
 */
class JvmAlbaApplication : AlbaApplication() {
    override fun attachBaseContext(base: Context) {
        loadNativeGraphics()
        super.attachBaseContext(base)
    }
}
