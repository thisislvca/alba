package dev.mela.app

import android.graphics.Bitmap
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mela.app.ui.loadThumbnail
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewDecodeTest {
    @Test fun previewFilesAreBoundedAndExifOrientationIsApplied() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "decode-regression-preview.jpg")
        try {
            val source = Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888)
            file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            source.recycle()
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val decoded = loadThumbnail(context, file.path, 512, true)
            assertNotNull(decoded)
            assertEquals(256, decoded!!.width)
            assertEquals(512, decoded.height)
            decoded.recycle()
        } finally { file.delete() }
    }
}
