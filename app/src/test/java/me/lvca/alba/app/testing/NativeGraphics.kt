package me.lvca.alba.app.testing

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File

/**
 * Initializes Robolectric's native graphics runtime on the calling (main) thread, once per test JVM.
 *
 * The runtime looks up its JNI class references (`java.nio` buffers, `FileDescriptor`) the first time any thread
 * needs them. When that first thread is a background decoder, such as a thumbnail decode on Dispatchers.IO, the lookup
 * fails and the runtime aborts the whole test JVM. Decoding one image from a file here makes the main thread do it.
 */
internal fun loadNativeGraphics() {
    if (loaded) return
    val file = File.createTempFile("alba-native-graphics", ".png")
    try {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)).recycle()
    } finally {
        file.delete()
    }
    loaded = true
}

@Volatile private var loaded = false
