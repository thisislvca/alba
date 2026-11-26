package me.lvca.alba.app.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The camera line of Info, formatted as Apple Photos and iCloud.com format it. */
class CameraFormatTest {
    private val en = Locale.US
    private val it = Locale.ITALY

    // An iPhone 15 Pro's main camera, as its HEIC records it.
    private val iPhone = CameraExif(make = "Apple", model = "iPhone 15 Pro", lensModel = "iPhone 15 Pro back triple camera 6.765mm f/1.78",
        fNumber = 1.7799999713880652, exposureSeconds = 0.013333333333333334, iso = 640, focalLengthMm = 6.765, focalLength35mm = 24)

    @Test fun fNumbersRoundToTwoDecimalsWithoutTrailingZeros() {
        assertEquals("1.78", CameraFormat.fNumber(1.7799999713880652, en))
        assertEquals("2.8", CameraFormat.fNumber(2.8, en))
        assertEquals("16", CameraFormat.fNumber(16.0, en))
        assertEquals("2.2", CameraFormat.fNumber(2.2000000476837203, en))
        // Half up from the decimal value, not from its binary neighbour.
        assertEquals("1.79", CameraFormat.fNumber(1.785, en))
        assertEquals("f/1.78", CameraFormat.aperture(1.7799999713880652, en))
        assertEquals("f/1,78", CameraFormat.aperture(1.7799999713880652, it))
    }

    @Test fun shortExposuresAreFractionsAndLongOnesSeconds() {
        assertEquals("1/75 s", CameraFormat.shutter(0.013333333333333334, en))
        assertEquals("1/8000 s", CameraFormat.shutter(1.0 / 8000, en))
        assertEquals("1/60 s", CameraFormat.shutter(0.016666, en))
        assertEquals("1/4 s", CameraFormat.shutter(0.25, en))
        assertEquals("1/2 s", CameraFormat.shutter(0.5, en))
        assertEquals("0.3 s", CameraFormat.shutter(0.3, en))
        assertEquals("0.8 s", CameraFormat.shutter(0.8, en))
        assertEquals("1 s", CameraFormat.shutter(1.0, en))
        assertEquals("2.5 s", CameraFormat.shutter(2.5, en))
        assertEquals("2,5 s", CameraFormat.shutter(2.5, it))
        assertEquals("30 s", CameraFormat.shutter(30.0, en))
    }

    @Test fun isoReadsLikeApple() = assertEquals("ISO 640", CameraFormat.iso(640))

    @Test fun focalLengthPrefersThe35mmEquivalent() {
        assertEquals("24 mm", CameraFormat.focalLength(iPhone, en))
        assertEquals("6.77 mm", CameraFormat.focalLength(iPhone.copy(focalLength35mm = null), en))
        assertEquals("6,77 mm", CameraFormat.focalLength(iPhone.copy(focalLength35mm = null), it))
        assertNull(CameraFormat.focalLength(iPhone.copy(focalLength35mm = null, focalLengthMm = null), en))
    }

    @Test fun deviceDropsTheRepeatedMake() {
        assertEquals("iPhone 15 Pro", CameraFormat.device(iPhone))
        assertEquals("Canon EOS R5", CameraFormat.device(CameraExif(make = "Canon", model = "Canon EOS R5")))
        assertEquals("NIKON Z 6", CameraFormat.device(CameraExif(make = "NIKON CORPORATION", model = "NIKON Z 6")))
        assertEquals("Google Pixel 9", CameraFormat.device(CameraExif(make = "Google", model = "Pixel 9")))
        assertEquals("Google", CameraFormat.device(CameraExif(make = "Google")))
        assertNull(CameraFormat.device(CameraExif()))
    }

    @Test fun iPhoneLensesAreNamedByRole() {
        assertEquals(LensRole.MAIN, CameraFormat.lensRole(iPhone))
        assertEquals(LensRole.MAIN, CameraFormat.lensRole(iPhone.copy(focalLength35mm = 48)))
        assertEquals(LensRole.ULTRA_WIDE, CameraFormat.lensRole(iPhone.copy(lensModel = "iPhone 15 Pro back triple camera 2.22mm f/2.2", focalLength35mm = 13)))
        assertEquals(LensRole.TELEPHOTO, CameraFormat.lensRole(iPhone.copy(lensModel = "iPhone 15 Pro back triple camera 9mm f/2.8", focalLength35mm = 77)))
        assertEquals(LensRole.FRONT, CameraFormat.lensRole(iPhone.copy(lensModel = "iPhone 15 Pro front camera 2.69mm f/1.9", focalLength35mm = 23)))
        assertEquals(LensRole.MAIN, CameraFormat.lensRole(CameraExif(make = "Apple", lensModel = "iPhone 8 back camera 3.99mm f/1.8")))
        // Several back cameras and no 35 mm focal length: which one can't be told.
        assertNull(CameraFormat.lensRole(iPhone.copy(focalLength35mm = null)))
        assertNull(CameraFormat.lensRole(CameraExif(make = "Canon", lensModel = "EF24-70mm f/2.8L II USM", focalLength35mm = 50)))
        assertNull(CameraFormat.lensRole(CameraExif(make = "Apple")))
    }

    @Test fun lensLineReadsLikeApplePhotos() {
        assertEquals("Main Camera — 24 mm ƒ1.78", CameraFormat.lensLine(iPhone, "Main Camera", en))
        // Apple's own lens description repeats the real focal length; without a role only the optics show.
        assertEquals("6.77 mm ƒ1.78", CameraFormat.lensLine(iPhone.copy(focalLength35mm = null), null, en))
        assertEquals("EF24-70mm f/2.8L II USM — 50 mm ƒ2.8",
            CameraFormat.lensLine(CameraExif(make = "Canon", lensModel = "EF24-70mm f/2.8L II USM", fNumber = 2.8, focalLength35mm = 50), null, en))
        assertEquals("Fotocamera principale — 24 mm ƒ1,78", CameraFormat.lensLine(iPhone, "Fotocamera principale", it))
        assertNull(CameraFormat.lensLine(CameraExif(make = "Google", model = "Pixel 9"), null, en))
    }

    @Test fun exposureListsWhatTheFileRecords() {
        assertEquals("ISO 640 · f/1.78 · 1/75 s", CameraFormat.exposure(iPhone, en))
        assertEquals("ISO 64 · 1/8000 s", CameraFormat.exposure(CameraExif(iso = 64, exposureSeconds = 1.0 / 8000), en))
        assertEquals("f/16 · 30 s", CameraFormat.exposure(CameraExif(fNumber = 16.0, exposureSeconds = 30.0), en))
        assertNull(CameraFormat.exposure(CameraExif(make = "Apple", model = "iPhone 15 Pro"), en))
    }

    @Test fun addedToICloudShowsOnlyWhenItCameMuchLater() {
        val taken = 1_784_000_000_000L
        assertFalse(addedLongAfterCapture(taken, taken + 5 * 60_000L))
        assertFalse(addedLongAfterCapture(taken, taken + 23 * 3_600_000L))
        assertTrue(addedLongAfterCapture(taken, taken + 24 * 3_600_000L))
        assertTrue(addedLongAfterCapture(taken, taken + 400L * 24 * 3_600_000L))
    }
}
