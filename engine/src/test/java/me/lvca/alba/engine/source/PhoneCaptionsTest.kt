package me.lvca.alba.engine.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneCaptionsTest {
    @Test
    fun cameraBoilerplateIsNotACaption() {
        listOf("default", "SONY DSC", "  OLYMPUS DIGITAL CAMERA  ", "Exif_JPEG_PICTURE", "IMG_1234", "DSC01234.JPG",
            "PXL_20240101_123456789", "   ", "-----", "\u0000\u0000").forEach { assertNull(it, PhoneCaptions.clean(it)) }
    }

    @Test
    fun shortRealCaptionsAreNotMistakenForFileNames() {
        listOf("P1", "Sam2024", "IMG", "Dsc party", "Vid night", "img 2", "P123").forEach { assertEquals(it, PhoneCaptions.clean(it)) }
        listOf("IMG_20240101_123456", "IMG_1234 (1).jpg", "_DSC1234", "DSC_1234", "DSCN1234", "DSCF1234.JPG", "CIMG1234",
            "IMAG0042", "SAM_1234", "GOPR0012", "DJI_0042", "P1010123", "MVIMG_20240101_123456", "VID_20240101_123456",
            "PXL_20240101_123456789.MP.jpg").forEach { assertNull(it, PhoneCaptions.clean(it)) }
    }

    @Test
    fun anEmptyOrSelfClosingXDefaultFallsBackToTheOtherLanguages() {
        val selfClosing = "<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\"/><rdf:li xml:lang=\"it\">Lago</rdf:li></rdf:Alt></dc:description>"
        assertEquals("Lago", PhoneCaptions.pick(selfClosing, imageDescription = "Other"))
        val empty = "<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\"> </rdf:li><rdf:li xml:lang=\"en\">Lake</rdf:li></rdf:Alt></dc:description>"
        assertEquals("Lake", PhoneCaptions.pick(empty, imageDescription = "Other"))
        val onlyEmpty = "<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\"/></rdf:Alt></dc:description>"
        assertEquals("Other", PhoneCaptions.pick(onlyEmpty, imageDescription = "Other"))
    }

    @Test
    fun aRealCaptionIsKeptTrimmedAndBounded() {
        assertEquals("Lake at dawn", PhoneCaptions.clean("  Lake at dawn\u0000 "))
        assertEquals("Sony DSC party", PhoneCaptions.clean("Sony DSC party"))
        assertEquals(PhoneCaptions.MAX_LENGTH, PhoneCaptions.clean("a".repeat(PhoneCaptions.MAX_LENGTH + 10))?.length)
    }

    @Test
    fun xmpDescriptionComesFirstAndIsUnescaped() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF><rdf:Description>
              <dc:description><rdf:Alt>
                <rdf:li xml:lang="it">Lago all&apos;alba</rdf:li>
                <rdf:li xml:lang="x-default">Lake &amp; mountains &#8212; dawn</rdf:li>
              </rdf:Alt></dc:description>
            </rdf:Description></rdf:RDF></x:xmpmeta>
        """.trimIndent()
        assertEquals("Lake & mountains — dawn", PhoneCaptions.pick(xmp, imageDescription = "SONY DSC"))
        assertEquals("Garden", PhoneCaptions.pick("""<rdf:Description dc:description="Garden"/>""", "Other"))
    }

    @Test
    fun imageDescriptionIsUsedWhenXmpHasNoCaption() {
        assertEquals("Birthday", PhoneCaptions.pick(xmp = null, imageDescription = "Birthday"))
        assertEquals("Birthday", PhoneCaptions.pick("<dc:description><rdf:Alt><rdf:li>default</rdf:li></rdf:Alt></dc:description>", "Birthday"))
        assertNull(PhoneCaptions.pick(xmp = "<x:xmpmeta/>", imageDescription = "default"))
    }
}
