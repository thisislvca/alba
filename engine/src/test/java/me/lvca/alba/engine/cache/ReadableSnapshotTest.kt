package me.lvca.alba.engine.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Loading the library checks its files against one listing per folder, and answers exactly as probing each would. */
@RunWith(AndroidJUnit4::class)
class ReadableSnapshotTest {
    private lateinit var context: Context
    private lateinit var cache: MediaFileCache

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cache = MediaFileCache(context, availableBytes = { Long.MAX_VALUE }).also { runBlocking { it.clearAll() } }
    }

    @After
    fun tearDown() {
        runBlocking { cache.clearAll() }
    }

    @Test
    fun theSnapshotAnswersAsProbingEachFileDoes() = runBlocking {
        val preview = cache.writeAtomically("icloud:a:1", "preview") { it.write(1) }
        val original = cache.writeAtomically("icloud:a:1", "original") { it.write(1) }
        val viewer = cache.writeAtomically("icloud:a:1", "viewer") { it.write(1) }
        val outside = File(context.filesDir, "elsewhere.jpg").apply { writeBytes(byteArrayOf(1)) }
        val paths = listOf(
            preview.path, original.path, viewer.path,
            // The thumbnail folder under the name the app gave it, rather than its canonical one.
            File(File(context.filesDir, "gallery_previews"), preview.name).absolutePath,
            File(preview.parentFile, "missing-preview.jpg").path,
            outside.path,
            preview.parent + "/../elsewhere.jpg",
            "relative/${preview.name}",
            null,
        )
        val snapshot = cache.readableSnapshot()

        assertEquals(paths.map(cache::isReadable), paths.map(snapshot))
        assertEquals(listOf(true, true, true, true, false, false, false, false, false), paths.map(snapshot))
    }
}
