package me.lvca.alba.app.platform

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.ui.ExternalUse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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
}
