package dev.mela.protocol.auth

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class TrustedDeviceBridgeCryptoTest {
    @Test
    fun proverMatchesOracleOnAndroidCryptoRuntime() {
        val prover = TrustedDeviceBridgeProver(scalarProvider = { BigInteger.valueOf(7) })
        prover.initialize("MDEyMzQ1Njc4OWFiY2RlZg==", "050044")

        assertEquals(CLIENT_MESSAGE_1, prover.message1())
        assertEquals(
            CLIENT_CONFIRMATION,
            prover.processServerProof(SERVER_MESSAGE_1, SERVER_CONFIRMATION),
        )
        assertEquals("derived-device-code", prover.decryptMessage(ENCRYPTED_CODE))
    }

    private companion object {
        const val CLIENT_MESSAGE_1 =
            "043137c66759f7121ea9ab252836b19827c4b8b40f21c28bb1c1cc1ab33af58c5d" +
                "3e7ea3071af7f93edb853ea7561cabd67fcd08b2b317283baf3a0f1e0d8d2f45"
        const val SERVER_MESSAGE_1 =
            "0401ec73c3be94b25aebdc419536e4afaaa535a4b2cf429291d108b7b19d660bda" +
                "a787c20331692e69def6cf7ce9cb5ad3bd8232997fe54afad1009baa5bfa30c3"
        const val SERVER_CONFIRMATION =
            "14026b9edbe9621d48629b2b35fa2a8ffbe702270e13377e340825b5b2a97230"
        const val CLIENT_CONFIRMATION =
            "1f7586725c99f09fa2c7dea461f4be661c5e02955490227c7edc3191bd943955"
        const val ENCRYPTED_CODE =
            "AAABAgMEBQYHCAkKC0smQh8wby8ykW2Q3IGbDo4UBYQG5opy/KYt95hLGuq9XAX5"
    }
}
