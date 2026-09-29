package dev.mela.app

import androidx.media3.datasource.DataSpec
import dev.mela.app.ui.LibraryDataSource
import dev.mela.engine.source.MediaRead
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackDataSourceTest {
    @Test fun opensProgressivelySeeksAndClosesEachRead() {
        val offsets = mutableListOf<Long>()
        var reads = 0
        var closes = 0
        val data = LibraryDataSource("video") { _, offset, _ ->
            offsets += offset
            object : MediaRead {
                override val length = 1000L
                override val input = object : ByteArrayInputStream(ByteArray(1000)) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int { reads += len; return super.read(b, off, len) }
                }
                override fun close() { closes++ }
            }
        }
        assertEquals(1000L, data.open(DataSpec.Builder().setUri("mela://playback").build()))
        assertEquals(0, reads)
        assertEquals(8, data.read(ByteArray(8), 0, 8))
        assertEquals(8, reads)
        data.close()
        data.open(DataSpec.Builder().setUri("mela://playback").setPosition(500).setLength(4).build())
        assertEquals(4, data.read(ByteArray(10), 0, 10))
        assertEquals(-1, data.read(ByteArray(1), 0, 1))
        data.close()
        assertEquals(listOf(0L, 500L), offsets)
        assertEquals(2, closes)
    }
}
