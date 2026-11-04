package me.lvca.alba.protocol

import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.account.PersistedCookie
import me.lvca.alba.protocol.livecontract.redact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live contract test's report is printed to CI logs, so its failure details must hold nothing that identifies the
 * account or could reuse the session. This runs with the normal JVM tests; the live test itself never does.
 */
class LiveContractRedactionTest {
    private val session = AppleSessionSnapshot(
        accountName = "alex@example.com",
        clientId = "auth-1b2c3d4e-0000-1111-2222-333344445555",
        accountCountryCode = "ITA",
        sessionId = "session-id-9988",
        sessionToken = "sessiontoken-abcdef0123456789",
        trustToken = "trusttoken-0123456789abcdef",
        dsid = "12345678901",
        webservices = emptyMap(),
        cookies = listOf(PersistedCookie("X-APPLE-WEBAUTH-TOKEN", "v=2:t=AQEBAAAAAGZ2b3Vc0d3", ".icloud.com", "/", null, true, true, false)),
        displayName = "Alex Example",
    )

    @Test
    fun failureDetailsKeepTheirMeaningButNoSecrets() {
        val raw = "IllegalStateException: lookup of 8F2A1C3B-4D5E-6F70-8192-A3B4C5D6E7F8 for IMG_0042.HEIC failed for " +
            "alex@example.com (dsid 12345678901) at https://p42-ckdatabasews.icloud.com/database/1/x?clientId=abc&dsid=12345678901 " +
            "with X-APPLE-WEBAUTH-TOKEN=v=2:t=AQEBAAAAAGZ2b3Vc0d3; scnt=AbCdEf123; \"sessionToken\": \"sessiontoken-abcdef0123456789\" " +
            "and record ARjhb1FQzP0vVd6vH2wZbFpS3Ek, Apple Photos returned HTTP 500 for Alex Example"

        val redacted = redact(raw, session)

        listOf(
            "8F2A1C3B", "IMG_0042", "alex@example.com", "12345678901", "clientId=abc", "AQEBAAAAAGZ2b3Vc0d3", "AbCdEf123",
            "sessiontoken-abcdef0123456789", "ARjhb1FQzP0vVd6vH2wZbFpS3Ek", "Alex Example",
        ).forEach { secret -> assertFalse("Leaked $secret in: $redacted", redacted.contains(secret)) }
        assertTrue("The failure stays readable: $redacted", redacted.contains("Apple Photos returned HTTP 500"))
        assertTrue(redacted.contains("IllegalStateException"))
        assertTrue(redacted.contains("<file>"))
    }

    @Test
    fun fieldNamesAndShortValuesAreKept() {
        val message = "missing on some records (count): CPLMaster.resOriginalRes.downloadURL (3), CPLAsset.assetDate (number) (1)"
        assertEquals(message, redact(message, session))
    }
}
