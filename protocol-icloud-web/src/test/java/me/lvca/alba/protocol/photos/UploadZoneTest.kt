package me.lvca.alba.protocol.photos

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** The zone registered with an upload: what iCloud web sends, unless the original records its capture offset. */
class UploadZoneTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val summerUpload = Instant.parse("2026-07-01T10:00:00Z")
    private val winterCapture = Instant.parse("2026-01-15T10:00:00Z")

    @Test
    fun withoutACaptureOffsetThePhonesZoneAtUploadTimeIsSentAsICloudWebDoes() {
        assertEquals("Europe/Rome" to 120, PhotosUpload.uploadZone(rome, summerUpload, winterCapture, null))
    }

    @Test
    fun aCaptureOffsetThePhonesZoneHadThenKeepsTheZoneWithTheCapturesOffset() {
        assertEquals("Europe/Rome" to 60, PhotosUpload.uploadZone(rome, summerUpload, winterCapture, 60))
    }

    @Test
    fun aCaptureOffsetFromElsewhereIsNamedByItsFixedOffsetZone() {
        assertEquals("Etc/GMT-9" to 540, PhotosUpload.uploadZone(rome, summerUpload, winterCapture, 540))
        assertEquals("Etc/GMT+5" to -300, PhotosUpload.uploadZone(rome, summerUpload, winterCapture, -300))
        assertEquals("Etc/UTC" to 0, PhotosUpload.uploadZone(rome, summerUpload, winterCapture, 0))
        // Both names are in the zone database.
        assertEquals(540 * 60, ZoneId.of("Etc/GMT-9").rules.getOffset(winterCapture).totalSeconds)
        assertEquals(-300 * 60, ZoneId.of("Etc/GMT+5").rules.getOffset(winterCapture).totalSeconds)
    }

    @Test
    fun anOffsetNoFixedZoneNamesFallsBackToWhatICloudWebSends() {
        assertEquals("Europe/Rome" to 120, PhotosUpload.uploadZone(rome, summerUpload, winterCapture, 330))
    }
}
