package dev.mela.protocol.photos

import java.util.Base64

/** Bounded share metadata image; never decode a full-size original for a tiny album cover. */
internal object SharedCoverImage {
    fun encode(encoded: ByteArray): String {
        require(encoded.size <= 4 * 1024 * 1024)
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
        require(options.outWidth > 0 && options.outHeight > 0)
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > 640) sample *= 2
        val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(encoded, 0, encoded.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }))
        try {
            val ratio = minOf(1f, 320f / maxOf(bitmap.width, bitmap.height))
            val thumbnail = android.graphics.Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * ratio).toInt()), maxOf(1, (bitmap.height * ratio).toInt()), true)
            try {
                val output = java.io.ByteArrayOutputStream()
                check(thumbnail.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, output))
                return Base64.getEncoder().encodeToString(output.toByteArray())
            } finally { if (thumbnail !== bitmap) thumbnail.recycle() }
        } finally { bitmap.recycle() }
    }
}
