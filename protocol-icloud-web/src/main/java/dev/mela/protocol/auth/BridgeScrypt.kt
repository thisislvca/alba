package dev.mela.protocol.auth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object BridgeScrypt {
    fun derive(
        password: ByteArray,
        salt: ByteArray,
        n: Int,
        r: Int,
        p: Int,
        outputBytes: Int,
    ): ByteArray {
        require(n > 1 && n and (n - 1) == 0) { "scrypt N must be a power of two" }
        require(r > 0 && p > 0 && outputBytes > 0) { "Invalid scrypt parameters" }
        val blockBytes = Math.multiplyExact(128, r)
        val initialBytes = Math.multiplyExact(blockBytes, p)
        val mixed = pbkdf2Sha256(password, salt, initialBytes)
        repeat(p) { block ->
            smix(mixed, block * blockBytes, n, r)
        }
        return pbkdf2Sha256(password, mixed, outputBytes)
    }

    private fun smix(value: ByteArray, offset: Int, n: Int, r: Int) {
        val blockBytes = 128 * r
        var x = value.copyOfRange(offset, offset + blockBytes)
        var y = ByteArray(blockBytes)
        val history = ByteArray(Math.multiplyExact(n, blockBytes))

        repeat(n) { round ->
            x.copyInto(history, destinationOffset = round * blockBytes)
            blockMix(x, y, r)
            val swap = x
            x = y
            y = swap
        }

        repeat(n) {
            val historyBlock = integerify(x, r).toInt() and (n - 1)
            val historyOffset = historyBlock * blockBytes
            for (index in x.indices) {
                x[index] = (x[index].toInt() xor history[historyOffset + index].toInt()).toByte()
            }
            blockMix(x, y, r)
            val swap = x
            x = y
            y = swap
        }
        x.copyInto(value, destinationOffset = offset)
    }

    private fun blockMix(input: ByteArray, output: ByteArray, r: Int) {
        val x = input.copyOfRange((2 * r - 1) * SALSA_BLOCK_BYTES, 2 * r * SALSA_BLOCK_BYTES)
        repeat(2 * r) { block ->
            val inputOffset = block * SALSA_BLOCK_BYTES
            for (index in 0 until SALSA_BLOCK_BYTES) {
                x[index] = (x[index].toInt() xor input[inputOffset + index].toInt()).toByte()
            }
            salsa208(x)
            x.copyInto(output, destinationOffset = inputOffset)
        }

        repeat(r) { block ->
            output.copyInto(
                destination = input,
                destinationOffset = block * SALSA_BLOCK_BYTES,
                startIndex = block * 2 * SALSA_BLOCK_BYTES,
                endIndex = (block * 2 + 1) * SALSA_BLOCK_BYTES,
            )
        }
        repeat(r) { block ->
            output.copyInto(
                destination = input,
                destinationOffset = (block + r) * SALSA_BLOCK_BYTES,
                startIndex = (block * 2 + 1) * SALSA_BLOCK_BYTES,
                endIndex = (block * 2 + 2) * SALSA_BLOCK_BYTES,
            )
        }
        input.copyInto(output)
    }

    private fun integerify(value: ByteArray, r: Int): Long {
        val offset = (2 * r - 1) * SALSA_BLOCK_BYTES
        return ByteBuffer.wrap(value, offset, Long.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .long
    }

    private fun salsa208(block: ByteArray) {
        val original = IntArray(16) { index -> readLittleEndianInt(block, index * Int.SIZE_BYTES) }
        val x = original.copyOf()
        repeat(4) {
            x[4] = x[4] xor rotateLeft(x[0] + x[12], 7)
            x[8] = x[8] xor rotateLeft(x[4] + x[0], 9)
            x[12] = x[12] xor rotateLeft(x[8] + x[4], 13)
            x[0] = x[0] xor rotateLeft(x[12] + x[8], 18)
            x[9] = x[9] xor rotateLeft(x[5] + x[1], 7)
            x[13] = x[13] xor rotateLeft(x[9] + x[5], 9)
            x[1] = x[1] xor rotateLeft(x[13] + x[9], 13)
            x[5] = x[5] xor rotateLeft(x[1] + x[13], 18)
            x[14] = x[14] xor rotateLeft(x[10] + x[6], 7)
            x[2] = x[2] xor rotateLeft(x[14] + x[10], 9)
            x[6] = x[6] xor rotateLeft(x[2] + x[14], 13)
            x[10] = x[10] xor rotateLeft(x[6] + x[2], 18)
            x[3] = x[3] xor rotateLeft(x[15] + x[11], 7)
            x[7] = x[7] xor rotateLeft(x[3] + x[15], 9)
            x[11] = x[11] xor rotateLeft(x[7] + x[3], 13)
            x[15] = x[15] xor rotateLeft(x[11] + x[7], 18)

            x[1] = x[1] xor rotateLeft(x[0] + x[3], 7)
            x[2] = x[2] xor rotateLeft(x[1] + x[0], 9)
            x[3] = x[3] xor rotateLeft(x[2] + x[1], 13)
            x[0] = x[0] xor rotateLeft(x[3] + x[2], 18)
            x[6] = x[6] xor rotateLeft(x[5] + x[4], 7)
            x[7] = x[7] xor rotateLeft(x[6] + x[5], 9)
            x[4] = x[4] xor rotateLeft(x[7] + x[6], 13)
            x[5] = x[5] xor rotateLeft(x[4] + x[7], 18)
            x[11] = x[11] xor rotateLeft(x[10] + x[9], 7)
            x[8] = x[8] xor rotateLeft(x[11] + x[10], 9)
            x[9] = x[9] xor rotateLeft(x[8] + x[11], 13)
            x[10] = x[10] xor rotateLeft(x[9] + x[8], 18)
            x[12] = x[12] xor rotateLeft(x[15] + x[14], 7)
            x[13] = x[13] xor rotateLeft(x[12] + x[15], 9)
            x[14] = x[14] xor rotateLeft(x[13] + x[12], 13)
            x[15] = x[15] xor rotateLeft(x[14] + x[13], 18)
        }
        x.indices.forEach { index ->
            writeLittleEndianInt(x[index] + original[index], block, index * Int.SIZE_BYTES)
        }
    }

    private fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, outputBytes: Int): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        // JCA rejects an empty SecretKeySpec even though HMAC defines an empty key.
        // A single zero byte produces the same padded HMAC key block.
        val hmacKey = if (password.isEmpty()) byteArrayOf(0) else password
        mac.init(SecretKeySpec(hmacKey, HMAC_SHA256))
        val hashBytes = mac.macLength
        val blocks = (outputBytes + hashBytes - 1) / hashBytes
        val result = ByteArray(outputBytes)
        var resultOffset = 0
        for (blockIndex in 1..blocks) {
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    (blockIndex ushr 24).toByte(),
                    (blockIndex ushr 16).toByte(),
                    (blockIndex ushr 8).toByte(),
                    blockIndex.toByte(),
                ),
            )
            val digest = mac.doFinal()
            val bytesToCopy = minOf(digest.size, outputBytes - resultOffset)
            digest.copyInto(result, resultOffset, endIndex = bytesToCopy)
            resultOffset += bytesToCopy
        }
        return result
    }

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun writeLittleEndianInt(value: Int, bytes: ByteArray, offset: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun rotateLeft(value: Int, bits: Int): Int = Integer.rotateLeft(value, bits)

    private const val SALSA_BLOCK_BYTES = 64
    private const val HMAC_SHA256 = "HmacSHA256"
}
