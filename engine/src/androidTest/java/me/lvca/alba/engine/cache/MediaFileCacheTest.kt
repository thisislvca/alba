package me.lvca.alba.engine.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MediaFileCacheTest {
    @Test
    fun writesSurviveAndroidPurgingTheCacheDirectory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = MediaFileCache(context)
        val bytes = byteArrayOf(1, 2, 3)

        // Android deletes cacheDir contents, directories included, when storage runs low.
        check(File(context.cacheDir, "gallery_media").deleteRecursively())

        for (variant in listOf("viewer", "shared")) {
            val file = cache.writeAtomically("purged-cache-$variant", variant) { it.write(bytes) }
            assertArrayEquals(bytes, file.readBytes())
            cache.delete(file.path)
        }
    }
}
