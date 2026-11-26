package me.lvca.alba.engine.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A CPLMaster is the upload's photo only when its original's size, checksum and fingerprint all equal the receipt's. */
class UploadReceiptMatchTest {
    private val receipt = UploadReceipt(byteCount = 1_234, fileChecksum = "AbCd==")

    @Test
    fun `size checksum and fingerprint equal to the receipt match`() {
        assertTrue(pair().matchesReceipt(receipt, byteCount = 1_234))
    }

    @Test
    fun `a size one byte off never matches`() {
        assertFalse(pair(size = 1_235).matchesReceipt(receipt, byteCount = 1_234))
        assertFalse("The sent count must be the receipt's too", pair().matchesReceipt(receipt, byteCount = 1_233))
    }

    @Test
    fun `a different checksum never matches`() {
        assertFalse(pair(checksum = "AbCe==").matchesReceipt(receipt, byteCount = 1_234))
    }

    @Test
    fun `a different fingerprint never matches`() {
        assertFalse(pair(fingerprint = "AbCe==").matchesReceipt(receipt, byteCount = 1_234))
    }

    @Test
    fun `a record that lacks any of the three never matches`() {
        assertFalse(pair(size = null).matchesReceipt(receipt, byteCount = 1_234))
        assertFalse(pair(checksum = null).matchesReceipt(receipt, byteCount = 1_234))
        assertFalse(pair(fingerprint = null).matchesReceipt(receipt, byteCount = 1_234))
    }

    private fun pair(size: Long? = 1_234, checksum: String? = "AbCd==", fingerprint: String? = "AbCd==") = RemotePairCandidate(
        RemoteRecordRef("master", "m"), RemoteRecordRef("asset", "a"),
        relationIsCurrent = true, masterDeleted = false, assetDeleted = false, originalResourceIdentity = "resource",
        originalByteCount = size, originalFileChecksum = checksum, originalFingerprint = fingerprint,
    )
}
