package me.lvca.alba.protocol.photos

import me.lvca.alba.engine.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Hidden, captions, locations, bursts and original formats, read from CPLAsset and CPLMaster records. */
class LibraryDataParsingTest {
    // Binary property lists written by Python's plistlib, as Photos stores `locationEnc`.
    private val harbour = "YnBsaXN0MDDVAQIDBAUGBwgJClNhbHRTbGF0U2xvblVzcGVlZFl0aW1lc3RhbXAjQBIAAAAAAAAjQEYm3F1jiGYjQCJrkjopx3oj" +
        "AAAAAAAAAAAzQcgcabwAAAAIExcbHyUvOEFKUwAAAAAAAAEBAAAAAAAAAAsAAAAAAAAAAAAAAAAAAABc"
    private val nullIsland = "YnBsaXN0MDDSAQIDA1NsYXRTbG9uIwAAAAAAAAAACA0RFQAAAAAAAAEBAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAe"
    private val integers = "YnBsaXN0MDDTAQIDBAUGU2xhdFNsb25UbmFtZRP/////////3xCXaABTAHkAZABuAGUAeQAgAOgIDxMXHCUnAAAAAAAAAQEAAAAAAAAABwAA" +
        "AAAAAAAAAAAAAAAAADg="

    private fun page(assetFields: String, itemType: String = "public.heic") = """{"records":[
        {"recordName":"photo","recordType":"CPLAsset","recordChangeTag":"a1","fields":{"masterRef":{"value":{"recordName":"master"}},
            "assetDate":{"value":1777777777000}$assetFields}},
        {"recordName":"master","recordType":"CPLMaster","recordChangeTag":"m1","fields":{
            "itemType":{"value":"$itemType"},"filenameEnc":{"value":"SU1HXzEuSEVJQw=="},
            "resOriginalRes":{"value":{"downloadURL":"https://cws.icloud-content.com/original"}}}}]}"""

    @Test fun binaryPlistReadsRealsIntegersStringsAndRejectsGarbage() {
        val location = BinaryPlist.parse(Base64.getDecoder().decode(harbour)) as Map<*, *>
        assertEquals(44.3036, location["lat"] as Double, 1e-9)
        assertEquals(9.2101, location["lon"] as Double, 1e-9)
        assertEquals(4.5, location["alt"] as Double, 1e-9)
        val other = BinaryPlist.parse(Base64.getDecoder().decode(integers)) as Map<*, *>
        assertEquals(-33L, other["lat"])
        assertEquals(151L, other["lon"])
        assertEquals("Sydney è", other["name"])
        assertNull(BinaryPlist.parse("bplist00".toByteArray()))
        assertNull(BinaryPlist.parse(ByteArray(64) { 0x7F }))
    }

    @Test fun captionsLocationTimeZoneAndBurstAreDecoded() {
        val photo = CloudKitPhotoResponseParser().parse(page(""",
            "captionEnc":{"value":"U3Vuc2V0IGF0IHRoZSBoYXJib3Vy"},"extendedDescEnc":{"value":"Q2l0dMOgIHZlY2NoaWE="},
            "locationEnc":{"value":"$harbour"},"timeZoneOffset":{"value":7200},"burstId":{"value":"BURST-1"}""")).photos.single()
        val details = photo.details
        assertEquals("Sunset at the harbour", details.caption)
        assertEquals("Città vecchia", details.description)
        assertEquals(44.3036, details.latitude!!, 1e-9)
        assertEquals(9.2101, details.longitude!!, 1e-9)
        assertEquals(4.5, details.altitude!!, 1e-9)
        assertEquals(7200, details.timeZoneOffsetSeconds)
        assertEquals("BURST-1", details.burstId)
        assertEquals("BURST-1", photo.toRemoteMediaRecord("id", 0, 0).details.burstId)
    }

    @Test fun missingOrUnsetDetailsStayEmpty() {
        val photo = CloudKitPhotoResponseParser().parse(page(""",
            "locationEnc":{"value":"$nullIsland"},"captionEnc":{"value":"  "},"timeZoneOffset":{"value":999999}""")).photos.single()
        assertNull(photo.details.caption)
        assertFalse(photo.details.hasLocation)
        assertNull(photo.details.timeZoneOffsetSeconds)
        assertNull(CloudKitPhotoResponseParser().parse(page(""",
            "locationEnc":{"value":"bm90IGEgcGxpc3Q="}""")).photos.single().details.latitude)
    }

    @Test fun hiddenAssetsAreFlaggedOnlyWhenTheCallerAsksForThem() {
        val hidden = page(""","isHidden":{"value":1}""")
        assertTrue(CloudKitPhotoResponseParser().parse(hidden).photos.isEmpty())
        val photo = CloudKitPhotoResponseParser().parse(hidden, includeHidden = true).photos.single()
        assertTrue(photo.isHidden)
        assertTrue(photo.toRemoteMediaRecord("id", 0, 0).isHidden)
        assertFalse(CloudKitPhotoResponseParser().parse(page("")).photos.single().isHidden)
    }

    @Test fun rawOriginalsKeepTheirOwnFormatInsteadOfJpeg() {
        val expected = mapOf(
            "com.adobe.raw-image" to "image/x-adobe-dng",
            "com.canon.cr3-raw-image" to "image/x-canon-cr3",
            "com.sony.arw-raw-image" to "image/x-sony-arw",
            "com.nikon.raw-image" to "image/x-nikon-nef",
            "com.example.new-raw-image" to "image/x-raw",
            "public.heic" to "image/heic",
            "public.jpeg" to "image/jpeg",
        )
        for ((uti, mime) in expected) {
            val photo = CloudKitPhotoResponseParser().parse(page("", itemType = uti)).photos.single()
            assertEquals(uti, mime, photo.mimeType)
            assertEquals(MediaKind.PHOTO, photo.kind)
        }
    }
}
