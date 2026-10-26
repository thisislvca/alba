package me.lvca.alba.app.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.editing.Adjustments
import me.lvca.alba.app.editing.EditRecipe
import me.lvca.alba.app.editing.LookFilter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The full-resolution save render: the recipe is applied row by row before rotation, with progress. EXIF and MediaStore are device tests. */
@RunWith(AndroidJUnit4::class)
class PhotoEditRenderTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun photo(): File = File.createTempFile("render", ".png", context.cacheDir).also { file ->
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(100, 100, 100)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun adjustmentsAndLooksReachTheSavedPixelsAndTheSourceIsUntouched() = runBlocking {
        val source = photo()
        val original = source.readBytes()
        val progress = mutableListOf<Float>()
        try {
            val result = renderPhotoEdit(source, PhotoCrop(), 1, recipe = EditRecipe(Adjustments(brightness = 60), LookFilter.WARM),
                progress = { progress += it })
            try {
                assertEquals(48, result.width); assertEquals(64, result.height)
                val pixel = result.getPixel(24, 32)
                assertTrue("Brighter", Color.green(pixel) > 110)
                assertTrue("Warmer", Color.red(pixel) > Color.blue(pixel))
            } finally { result.recycle() }
            assertEquals(1f, progress.last())
            assertArrayEquals(original, source.readBytes())
        } finally { source.delete() }
    }

    @Test fun anUneditedRecipeKeepsThePixels() = runBlocking {
        val source = photo()
        try {
            val result = renderPhotoEdit(source, PhotoCrop(), 0)
            try { assertEquals(Color.rgb(100, 100, 100), result.getPixel(10, 10)) } finally { result.recycle() }
        } finally { source.delete() }
    }
}
