package me.lvca.alba.engine.repository

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class SharedUploadPreparationTest {
    @Test fun preparesOffCallerThreadAndExposesExactBytesOnlyOnce() = runBlocking {
        val caller = Thread.currentThread()
        var preparationThread: Thread? = null
        val original = File.createTempFile("shared-upload", ".jpg")
        val file = object : File(original.path) {
            override fun length(): Long {
                preparationThread = Thread.currentThread()
                return super.length()
            }
        }
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()) + ByteArray(140_000) { it.toByte() }
        try {
            file.writeBytes(bytes)
            val prepared = prepareSharedUpload(file)
            assertNotSame(caller, preparationThread)
            assertEquals(bytes.size.toLong(), prepared.source.byteCount)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, prepared.source.sha256Hex)
            assertArrayEquals(bytes, prepared.source.openOnce().use { it.readBytes() })
            assertThrows(IllegalStateException::class.java) { prepared.source.openOnce() }
            Unit
        } finally { file.delete() }
    }
}
