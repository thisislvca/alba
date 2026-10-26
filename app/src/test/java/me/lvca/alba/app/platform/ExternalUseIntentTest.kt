package me.lvca.alba.app.platform

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.testing.loadNativeGraphics
import me.lvca.alba.app.ui.ExternalUse
import me.lvca.alba.engine.model.OriginalExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.OutputStream

@RunWith(AndroidJUnit4::class)
class ExternalUseIntentTest {
    private val copy = Uri.parse("content://me.lvca.alba.shares/shares/1/1-IMG_0001.JPG")

    @Test fun eachUseNamesItsSystemActionAndGrantsReadAccess() {
        mapOf(ExternalUse.USE_AS to Intent.ACTION_ATTACH_DATA, ExternalUse.OPEN_WITH to Intent.ACTION_VIEW,
            ExternalUse.EDIT_IN to Intent.ACTION_EDIT).forEach { (use, action) ->
            val intent = requireNotNull(externalUseIntent(use, copy, "image/jpeg"))
            assertEquals(action, intent.action)
            assertEquals(copy, intent.data)
            assertEquals("image/jpeg", intent.type)
            assertEquals(copy, intent.clipData?.getItemAt(0)?.uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }
    }

    @Test fun onlyEditorsMayWriteTheCopyAndWallpaperPickersGetTheType() {
        assertTrue(externalUseIntent(ExternalUse.EDIT_IN, copy, "image/jpeg")!!.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertEquals(0, externalUseIntent(ExternalUse.OPEN_WITH, copy, "image/jpeg")!!.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals("image/jpeg", externalUseIntent(ExternalUse.USE_AS, copy, "image/jpeg")!!.getStringExtra("mimeType"))
    }

    @Test fun printingIsDoneByAlbaNotAnotherApp() {
        assertNull(externalUseIntent(ExternalUse.PRINT, copy, "image/jpeg"))
    }

    @Test fun printingChecksThatAndroidCanDecodeTheImageFirst() {
        loadNativeGraphics()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val exporter = GalleryExporter(context, object : OriginalExporter {
            override suspend fun exportOriginal(mediaId: String, output: OutputStream, motion: Boolean) = error("Unused")
        })
        val photo = File(context.cacheDir, "printable.png").apply {
            outputStream().use { Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val unreadable = File(context.cacheDir, "unreadable.heic").apply { writeText("not an image") }
        runBlocking {
            assertTrue(exporter.canDecodeImage(Uri.fromFile(photo)))
            assertFalse(exporter.canDecodeImage(Uri.fromFile(unreadable)))
        }
    }
}
