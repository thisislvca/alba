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
