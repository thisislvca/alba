package me.lvca.alba.protocol.photos

/**
 * A minimal reader for Apple's binary property lists (`bplist00`), enough for the small dictionaries iCloud Photos
 * stores in encrypted asset fields such as `locationEnc`. Returns null for anything malformed rather than throwing,
 * because this data is optional metadata.
 *
 * Values map to Kotlin as: booleans to [Boolean], integers to [Long], reals and dates to [Double] (dates as seconds
 * since 2001-01-01), strings to [String], data to [ByteArray], arrays to [List] and dictionaries to [Map].
 */
internal object BinaryPlist {
    fun parse(bytes: ByteArray): Any? = runCatching { Reader(bytes).root() }.getOrNull()

    private class Reader(private val bytes: ByteArray) {
        private val offsetSize: Int
        private val refSize: Int
        private val objectCount: Int
        private val topObject: Int
        private val offsetTable: Int

        init {
            require(bytes.size >= MAGIC.size + TRAILER_SIZE && bytes.copyOf(MAGIC.size).contentEquals(MAGIC))
            val trailer = bytes.size - TRAILER_SIZE
            offsetSize = byte(trailer + TRAILER_OFFSET_SIZE)
            refSize = byte(trailer + TRAILER_REF_SIZE)
            objectCount = uint(trailer + TRAILER_OBJECT_COUNT, LONG_BYTES).toIndex()
            topObject = uint(trailer + TRAILER_TOP_OBJECT, LONG_BYTES).toIndex()
            offsetTable = uint(trailer + TRAILER_OFFSET_TABLE, LONG_BYTES).toIndex()
            require(offsetSize in 1..LONG_BYTES && refSize in 1..LONG_BYTES && topObject in 0 until objectCount)
            require(offsetTable >= MAGIC.size && offsetTable.toLong() + objectCount.toLong() * offsetSize <= trailer)
        }

        /** Objects read so far; a malformed list could otherwise reference the same objects without end. */
        private var nodes = 0

        fun root(): Any? = read(topObject, 0)

        private fun byte(at: Int): Int = bytes[at].toInt() and BYTE_MASK

        private fun uint(at: Int, size: Int): Long {
            require(at >= 0 && at + size <= bytes.size)
            var value = 0L
            for (i in 0 until size) value = (value shl Byte.SIZE_BITS) or byte(at + i).toLong()
            return value
        }

        private fun Long.toIndex(): Int { require(this in 0..Int.MAX_VALUE); return toInt() }

        private fun offsetOf(ref: Int): Int {
            require(ref in 0 until objectCount)
            return uint(offsetTable + ref * offsetSize, offsetSize).toIndex().also { require(it in MAGIC.size until offsetTable) }
        }

        private fun ref(at: Int): Int = uint(at, refSize).toIndex()

        /** The count that follows a marker: inline in its low nibble, or in a following integer when the nibble is 0xF. */
        private fun count(at: Int, low: Int): Pair<Int, Int> {
            if (low != NIBBLE) return low to at + 1
            val marker = byte(at + 1)
            require(marker shr NIBBLE_BITS == TYPE_INT)
            val size = 1 shl (marker and NIBBLE)
            return uint(at + 2, size).toIndex() to at + 2 + size
        }

        private fun read(ref: Int, depth: Int): Any? {
            require(depth < MAX_DEPTH && ++nodes <= MAX_NODES)
            val at = offsetOf(ref)
            val marker = byte(at)
            val low = marker and NIBBLE
            return when (marker shr NIBBLE_BITS) {
                TYPE_SIMPLE -> when (marker) { FALSE -> false; TRUE -> true; else -> null }
                // One, two and four byte integers are unsigned; eight byte integers are signed, which shl keeps.
                TYPE_INT -> (1 shl low).let { size -> require(size <= LONG_BYTES); uint(at + 1, size) }
                TYPE_REAL -> when (1 shl low) {
                    Float.SIZE_BYTES -> Float.fromBits(uint(at + 1, Float.SIZE_BYTES).toInt()).toDouble()
                    Double.SIZE_BYTES -> Double.fromBits(uint(at + 1, Double.SIZE_BYTES))
                    else -> null
                }
                TYPE_DATE -> Double.fromBits(uint(at + 1, Double.SIZE_BYTES))
                TYPE_DATA -> count(at, low).let { (size, start) -> slice(start, size) }
                TYPE_ASCII -> count(at, low).let { (size, start) -> String(slice(start, size), Charsets.US_ASCII) }
                TYPE_UTF16 -> count(at, low).let { (size, start) -> String(slice(start, size * 2), Charsets.UTF_16BE) }
                TYPE_ARRAY -> count(at, low).let { (size, start) ->
                    requireRefs(start, size)
                    List(size) { read(ref(start + it * refSize), depth + 1) }
                }
                TYPE_DICT -> count(at, low).let { (size, start) -> requireRefs(start, size * 2); dictionary(size, start, depth) }
                else -> null
            }
        }

        /** [size] references fit in the bytes after [start], so a forged count cannot allocate past the input. */
        private fun requireRefs(start: Int, size: Int) {
            require(size >= 0 && size.toLong() * refSize <= (bytes.size - start).toLong())
        }

        private fun dictionary(size: Int, start: Int, depth: Int): Map<String, Any?> = buildMap {
            for (index in 0 until size) {
                val key = read(ref(start + index * refSize), depth + 1) as? String ?: continue
                put(key, read(ref(start + (size + index) * refSize), depth + 1))
            }
        }

        private fun slice(start: Int, size: Int): ByteArray {
            require(size >= 0 && start + size <= bytes.size)
            return bytes.copyOfRange(start, start + size)
        }
    }
}

private val MAGIC = "bplist00".toByteArray(Charsets.US_ASCII)
private const val TRAILER_SIZE = 32
private const val TRAILER_OFFSET_SIZE = 6
private const val TRAILER_REF_SIZE = 7
private const val TRAILER_OBJECT_COUNT = 8
private const val TRAILER_TOP_OBJECT = 16
private const val TRAILER_OFFSET_TABLE = 24
private const val LONG_BYTES = Long.SIZE_BYTES
private const val BYTE_MASK = 0xFF
private const val NIBBLE = 0xF
private const val NIBBLE_BITS = 4
private const val MAX_DEPTH = 16
private const val MAX_NODES = 10_000
private const val FALSE = 0x08
private const val TRUE = 0x09
private const val TYPE_SIMPLE = 0x0
private const val TYPE_INT = 0x1
private const val TYPE_REAL = 0x2
private const val TYPE_DATE = 0x3
private const val TYPE_DATA = 0x4
private const val TYPE_ASCII = 0x5
private const val TYPE_UTF16 = 0x6
private const val TYPE_ARRAY = 0xA
private const val TYPE_DICT = 0xD
