package me.lvca.alba.engine.source

import java.io.ByteArrayOutputStream

/**
 * Synthetic motion photos, built byte by byte as each camera writes them, so tests never need personal media: a JPEG
 * with an XMP packet in APP1, the MP4 appended after it, and for Samsung's own format the `MotionPhoto_Data` block and
 * SEF trailer instead of XMP.
 */
internal object MotionPhotoFixtures {
    /** A minimal MP4: ftyp, a movie header of [durationMs], one video track of [width] by [height], and some media data. */
    fun mp4(durationMs: Long = 3_000, width: Int = 1920, height: Int = 1080, quarterTurn: Boolean = true, seed: Int = 1): ByteArray {
        val ftyp = box("ftyp", ascii("isom") + int32(512) + ascii("isomiso2avc1mp41"))
        val mvhd = box("mvhd", int32(0) + int32(0) + int32(0) + int32(1_000) + int32(durationMs) + ByteArray(80))
        val matrix = if (quarterTurn) {
            int32(0) + int32(0x00010000) + int32(0) + int32(0xFFFF0000) + int32(0) + int32(0) + int32(0) + int32(0) + int32(0x40000000)
        } else {
            int32(0x00010000) + int32(0) + int32(0) + int32(0) + int32(0x00010000) + int32(0) + int32(0) + int32(0) + int32(0x40000000)
        }
        val tkhd = box(
            "tkhd",
            int32(3) + int32(0) + int32(0) + int32(1) + int32(0) + int32(durationMs) + ByteArray(8) + ByteArray(8) + matrix +
                int32(width.toLong() shl 16) + int32(height.toLong() shl 16),
        )
        val hdlr = box("hdlr", int32(0) + int32(0) + ascii("vide") + ByteArray(12) + byteArrayOf(0))
        val trak = box("trak", tkhd + box("mdia", hdlr))
        val moov = box("moov", mvhd + trak)
        val mdat = box("mdat", ByteArray(300) { (it * 7 + seed).toByte() })
        return ftyp + moov + mdat
    }

    /** A JPEG whose APP1 holds [xmp], with some image data and its end marker; [padding] zero bytes follow it. */
    fun jpeg(xmp: String?, padding: Int = 0, seed: Int = 3): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        if (xmp != null) {
            val payload = ascii("http://ns.adobe.com/xap/1.0/") + byteArrayOf(0) + xmp.toByteArray(Charsets.UTF_8)
            out.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte()))
            out.write(byteArrayOf(((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()))
            out.write(payload)
        }
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 4, 1, 2))
        out.write(ByteArray(200) { (it * 13 + seed).toByte() })
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        out.write(ByteArray(padding))
        return out.toByteArray()
    }

    /** Google Motion Photo v1 as a Pixel writes it: Camera:MotionPhoto and a Container:Directory of attributes. */
    fun pixelMotionPhoto(video: ByteArray = mp4(), timestampUs: Long = 1_500_000): ByteArray = jpeg(
        """
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about=""
                xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                xmlns:Container="http://ns.google.com/photos/1.0/container/"
                xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
              GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1"
              GCamera:MotionPhotoPresentationTimestampUs="$timestampUs">
              <Container:Directory>
                <rdf:Seq>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>
                  </rdf:li>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="${video.size}" Item:Padding="0"/>
                  </rdf:li>
                </rdf:Seq>
              </Container:Directory>
            </rdf:Description>
          </rdf:RDF>
        </x:xmpmeta>
        """.trimIndent(),
    ) + video

    /**
     * Google Motion Photo v1 as newer Samsung, OnePlus and Oppo phones write it: other prefixes for the same namespaces,
     * the item fields as elements, padding after the primary image and no presentation time.
     */
    fun samsungMotionPhoto(video: ByteArray = mp4(), padding: Int = 8): ByteArray = jpeg(
        """
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
                xmlns:Container="http://ns.google.com/photos/1.0/container/"
                xmlns:Item="http://ns.google.com/photos/1.0/container/item/">
              <Camera:MotionPhoto>1</Camera:MotionPhoto>
              <Camera:MotionPhotoVersion>1</Camera:MotionPhotoVersion>
              <Container:Directory>
                <rdf:Seq>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item><rdf:Description><Item:Mime>image/jpeg</Item:Mime><Item:Semantic>Primary</Item:Semantic>
                      <Item:Padding>$padding</Item:Padding></rdf:Description></Container:Item>
                  </rdf:li>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item><rdf:Description><Item:Mime>video/mp4</Item:Mime><Item:Semantic>MotionPhoto</Item:Semantic>
                      <Item:Length>${video.size}</Item:Length></rdf:Description></Container:Item>
                  </rdf:li>
                </rdf:Seq>
              </Container:Directory>
            </rdf:Description>
          </rdf:RDF>
        </x:xmpmeta>
        """.trimIndent(),
        padding = padding,
    ) + video

    /** Google's older MicroVideo tags, as older Pixels ("MVIMG") and Xiaomi, Redmi and POCO phones write them. */
    fun microVideo(video: ByteArray = mp4(), timestampUs: Long = 900_000): ByteArray = jpeg(
        """
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
              GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1" GCamera:MicroVideoOffset="${video.size}"
              GCamera:MicroVideoPresentationTimestampUs="$timestampUs"/>
          </rdf:RDF>
        </x:xmpmeta>
        """.trimIndent(),
    ) + video

    /**
     * Samsung's own format on older Galaxy phones: no XMP, the MP4 in a SEF data block named `MotionPhoto_Data` after the
     * JPEG, and the SEF directory and `SEFT` footer at the end of the file.
     */
    fun samsungTrailer(video: ByteArray = mp4()): ByteArray {
        val name = ascii("MotionPhoto_Data")
        val block = byteArrayOf(0, 0, 0x30, 0x0A) + le32(name.size) + name + video
        val directory = ascii("SEFH") + le32(106) + le32(1) + byteArrayOf(0, 0, 0x30, 0x0A) + le32(block.size + 8) + le32(block.size)
        return jpeg(null) + block + directory + le32(directory.size) + ascii("SEFT")
    }

    /** A JPEG with ordinary XMP and no video. */
    fun plainPhoto(): ByteArray = jpeg(
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/"/></rdf:RDF></x:xmpmeta>""",
    )

    fun box(type: String, body: ByteArray): ByteArray = int32(8L + body.size) + ascii(type) + body
    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)
    private fun int32(value: Long) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
    private fun int32(value: Int) = int32(value.toLong() and 0xFFFFFFFFL)
    private fun le32(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())
}
