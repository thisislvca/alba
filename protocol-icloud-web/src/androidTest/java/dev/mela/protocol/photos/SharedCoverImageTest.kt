package dev.mela.protocol.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class SharedCoverImageTest {
    @Test fun largeCoverBecomesSmallJpegAndInvalidInputIsRejected() {
        val source = Bitmap.createBitmap(2400,800,Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.JPEG,90,it) }.toByteArray()
        source.recycle()
        val encoded = Base64.getDecoder().decode(SharedCoverImage.encode(bytes))
        val bitmap = BitmapFactory.decodeByteArray(encoded,0,encoded.size)!!
        try {
            assertEquals(320,bitmap.width)
            assertTrue(bitmap.height in 105..107)
            assertTrue(encoded.size < 100_000)
            assertEquals(0xff,encoded[0].toInt() and 255)
            assertEquals(0xd8,encoded[1].toInt() and 255)
        } finally { bitmap.recycle() }
        assertTrue(runCatching { SharedCoverImage.encode(byteArrayOf(1,2,3)) }.isFailure)
        assertTrue(runCatching { SharedCoverImage.encode(ByteArray(4 * 1024 * 1024 + 1)) }.isFailure)
    }
}
