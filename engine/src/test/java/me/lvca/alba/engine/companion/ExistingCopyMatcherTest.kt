package me.lvca.alba.engine.companion

import me.lvca.alba.engine.model.LibraryCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

class ExistingCopyMatcherTest {
    private val phone = PhoneOriginal("IMG_1234.HEIC", byteCount = 2_000_000, sha256Hex = "ab", capturedAtEpochMillis = CAPTURED)

    @Test
    fun `same size, name and capture time is the one candidate to check`() {
        val match = item("a", "img_1234.heic", 2_000_000, CAPTURED + 1_000)
        val selection = ExistingCopyMatcher.select(phone, listOf(match, item("b", "other.jpg", 2_000_000, CAPTURED - 86_400_000)))

        assertEquals(CandidateSelection.Check(listOf(match)), selection)
    }

    @Test
    fun `a size that differs by one byte is never a candidate`() {
        val selection = ExistingCopyMatcher.select(phone, listOf(item("a", "IMG_1234.HEIC", 2_000_001, CAPTURED)))

        assertEquals(CandidateSelection.None, selection)
    }

    @Test
    fun `same size alone is not enough to download anything`() {
        val selection = ExistingCopyMatcher.select(phone, listOf(item("a", "IMG_9999.HEIC", 2_000_000, CAPTURED - 60_000)))

        assertEquals(CandidateSelection.None, selection)
    }

    @Test
    fun `a renamed copy with the same capture time is still checked`() {
        val renamed = item("a", "Exported photo.heic", 2_000_000, CAPTURED)

        assertEquals(CandidateSelection.Check(listOf(renamed)), ExistingCopyMatcher.select(phone, listOf(renamed)))
    }

    @Test
    fun `a phone file without a capture time is matched by name`() {
        val named = item("a", "IMG_1234.HEIC", 2_000_000, CAPTURED)
        val selection = ExistingCopyMatcher.select(phone.copy(capturedAtEpochMillis = null), listOf(named))

        assertEquals(CandidateSelection.Check(listOf(named)), selection)
    }

    @Test
    fun `items agreeing on both name and time win over those agreeing on one`() {
        val both = item("a", "IMG_1234.HEIC", 2_000_000, CAPTURED)
        val nameOnly = listOf("b", "c", "d").map { item(it, "IMG_1234.HEIC", 2_000_000, CAPTURED + 3_600_000) }

        assertEquals(CandidateSelection.Check(listOf(both)), ExistingCopyMatcher.select(phone, nameOnly + both))
    }

    @Test
    fun `more look-alikes than the download budget are ambiguous`() {
        val alike = (1..3).map { item("$it", "IMG_1234.HEIC", 2_000_000, CAPTURED) }

        assertEquals(CandidateSelection.TooMany(3), ExistingCopyMatcher.select(phone, alike))
    }

    @Test
    fun `two look-alikes are both checked`() {
        val alike = (1..2).map { item("$it", "IMG_1234.HEIC", 2_000_000, CAPTURED) }

        assertEquals(CandidateSelection.Check(alike), ExistingCopyMatcher.select(phone, alike))
    }

    @Test
    fun `a size shared by a crowd of library items is ambiguous without looking further`() {
        val crowd = (1..ExistingCopyMatcher.MAX_SAME_SIZE).map { item("$it", "x$it.jpg", 2_000_000, 0) }

        assertEquals(CandidateSelection.TooMany(ExistingCopyMatcher.MAX_SAME_SIZE), ExistingCopyMatcher.select(phone, crowd))
    }

    @Test
    fun `a phone file without a hash is never matched`() {
        val selection = ExistingCopyMatcher.select(phone.copy(sha256Hex = ""), listOf(item("a", "IMG_1234.HEIC", 2_000_000, CAPTURED)))

        assertEquals(CandidateSelection.None, selection)
    }

    private fun item(name: String, fileName: String, size: Long, capturedAt: Long) =
        LibraryCandidate("icloud:ns:$name", "master-$name", "asset-$name", fileName, size, capturedAt)

    private companion object {
        const val CAPTURED = 1_700_000_000_000L
    }
}
