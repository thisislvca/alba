package dev.mela.engine.source

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.mela.engine.cache.UploadStagingStore
import dev.mela.engine.cache.InsufficientStorageException
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UploadFormatsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun fixture(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open("upload-formats/$name").use { it.readBytes() }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun verifiedPhotosAndBothVideoCodecsInBothContainersStageWithoutConversion() = runBlocking {
        val resolver = context.contentResolver
        val source = AndroidMediaStoreSource(context)
        val staging = UploadStagingStore(context)
        val cases = listOf("jpeg.jpg" to "image/jpeg", "png.png" to "image/png", "heic.heic" to "image/heic",
            "webp.webp" to "image/webp", "avc.mp4" to "video/mp4", "hevc.mov" to "video/quicktime",
            "avc.mov" to "video/quicktime", "hevc.mp4" to "video/mp4")
        for ((name,mime) in cases) {
            val bytes = fixture(name)
            val video = mime.startsWith("video/")
            val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = requireNotNull(resolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "mela-format-${System.nanoTime()}-$name")
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/MelaTests" else "Pictures/MelaTests")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
            try {
                resolver.openOutputStream(uri)!!.use { it.write(bytes) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                val staged = staging.stageExact { source.copySelected(uri.toString(), name, it) }
                try {
                    assertEquals(name, staged.evidence.displayName)
                    assertEquals(mime, staged.evidence.mimeType)
                    assertEquals(bytes.size.toLong(), staged.byteCount)
                    assertEquals(digest(bytes), staged.sha256Hex)
                    assertTrue(staged.evidence.formatValidated)
                    assertEquals(android.os.Build.VERSION.SDK_INT >= 31, staged.evidence.originalFormatRequested)
                    assertNotNull(staged.evidence.lastModifiedAtEpochMillis)
                    val upload = staging.openVerified(staged.ownedFileToken, staged.byteCount, staged.sha256Hex)
                    assertArrayEquals(bytes, upload.openOnce().use { it.readBytes() })
                    assertTrue(runCatching { upload.openOnce() }.isFailure)
                } finally { staging.deleteOwned(staged.ownedFileToken) }
            } finally { resolver.delete(uri,null,null) }
        }
    }

    @Test fun animatedAndUnsupportedCodecFilesNeverBecomeStages() = runBlocking {
        val folder = File(context.filesDir, "upload_staging").apply { mkdirs() }
        val before = folder.list()!!.toSet()
        val staging = UploadStagingStore(context)
        for (name in listOf("animated.webp", "animated.png", "vp9.mp4", "animated.gif")) {
            val bytes = fixture(name)
            val result = runCatching { staging.stageExact { output ->
                val format = UploadMediaFormat.detect(bytes.take(4096).toByteArray())
                output.write(bytes)
                ExactOriginalEvidence("content://test/$name",name,format.mimeType,"lineage","revision","USER_SELECTION",false,true,true,bytes.size.toLong(),digest(bytes))
            } }
            assertTrue("$name must be rejected: $result", result.exceptionOrNull() is UnsupportedUploadException)
            assertEquals(before,folder.list()!!.toSet())
        }
    }

    @Test fun lowStorageAndCancellationRemovePartialOriginals() = runBlocking {
        val folder = File(context.filesDir,"upload_staging").apply { mkdirs() }
        val before = folder.list()!!.toSet()
        val bytes = fixture("png.png")
        val noSpace = runCatching { UploadStagingStore(context, availableBytes = { 0L }).stageExact { output ->
            output.write(bytes)
            error("Storage guard should stop this write")
        } }
        assertTrue(noSpace.exceptionOrNull() is InsufficientStorageException)
        assertEquals(before,folder.list()!!.toSet())
        val job = launch { UploadStagingStore(context).stageExact { output ->
            currentCoroutineContext().cancel()
            output.write(bytes)
            error("Cancelled write continued")
        } }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(before,folder.list()!!.toSet())
    }
}
