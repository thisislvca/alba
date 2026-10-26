package me.lvca.alba.app.intake

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Fragment matching is done by Android itself from API 35, so only a device can check it. */
@RunWith(AndroidJUnit4::class)
class SharedAlbumLinkRoutingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun albaHandles(link: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
        return context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }

    @Test fun sharedAlbumFragmentsOpenAlbaButTheICloudPhotosHomeDoesNot() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
        assertTrue(albaHandles("https://www.icloud.com/photos#/sharedalbums/sc,Token1"))
        assertTrue(albaHandles("https://www.icloud.com/photos/#sc,Token2"))
        assertFalse(albaHandles("https://www.icloud.com/photos/"))
        assertFalse(albaHandles("https://www.icloud.com/photos/#/library"))
    }
}
