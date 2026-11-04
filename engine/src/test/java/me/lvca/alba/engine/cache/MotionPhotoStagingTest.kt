package me.lvca.alba.engine.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.lvca.alba.engine.source.ExactOriginalEvidence
import me.lvca.alba.engine.source.UnsupportedUploadException
import me.lvca.alba.engine.source.UploadMediaFormat
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * iCloud web cannot pair a still with a video into a Live Photo, so a Motion Photo is uploaded as its still, byte for
 * byte: the MP4 inside it is kept, never cut off. Formats iCloud web does not take never become a staged copy.
 */
@RunWith(AndroidJUnit4::class)
class MotionPhotoStagingTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val staging by lazy { UploadStagingStore(context) }

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("upload-formats/$name")) { name }.use { it.readBytes() }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun stage(name: String, bytes: ByteArray, mimeType: String) = staging.stageExact { output ->
        output.write(bytes)
        ExactOriginalEvidence("content://test/$name", name, mimeType, "lineage", "revision", "USER_SELECTION", true, true, true,
            bytes.size.toLong(), digest(bytes))
    }

    @After fun tearDown() { staging.sweepOrphans(emptySet(), minimumAgeMillis = 0) }

    @Test
    fun aMotionPhotoIsStagedWithItsEmbeddedVideoIntact() = runBlocking {
        val bytes = fixture("PXL_20240501_101010123.MP.jpg")
        val clip = fixture("clip.mp4")
        val staged = stage("PXL_20240501_101010123.MP.jpg", bytes, UploadMediaFormat.JPEG.mimeType)

        assertEquals(bytes.size.toLong(), staged.byteCount)
        assertEquals(digest(bytes), staged.sha256Hex)
        val uploaded = staging.openVerified(staged.ownedFileToken, staged.byteCount, staged.sha256Hex).openOnce().use { it.readBytes() }
        assertArrayEquals(bytes, uploaded)
        assertArrayEquals("The motion clip must still end the file", clip, uploaded.copyOfRange(uploaded.size - clip.size, uploaded.size))
    }

    @Test
    fun formatsICloudWebDoesNotTakeAreRefusedBeforeAnyCopyIsKept() = runBlocking {
        val reasons = mapOf(
            "still.gif" to UnsupportedUploadException.GIF,
            "dng.dng" to UnsupportedUploadException.RAW,
            "3gp-only.3gp" to UnsupportedUploadException.VIDEO_CONTAINER,
        )
        for ((name, reason) in reasons) {
            val result = runCatching { stage(name, fixture(name), "image/jpeg") }
            val error = result.exceptionOrNull()
            assertTrue("$name must be refused: $result", error is UnsupportedUploadException)
            assertEquals(name, reason, error?.message)
        }
        assertEquals(0, staging.sweepOrphans(emptySet(), minimumAgeMillis = 0))
    }
}
