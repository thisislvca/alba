package dev.mela.protocol.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Hsa2BootContextTest {
    @Test
    fun `boot args parser accepts reordered attributes and nested bridge data`() {
        val context = requireNotNull(
            Hsa2BootContext.parse(
                """
                <html>
                  <script nonce="abc" class="boot_args extra" type="application/json">
                    {
                      "direct": {
                        "authInitialRoute": "auth/bridge/step",
                        "hasTrustedDevices": true,
                        "twoSV": {
                          "authFactors": ["web_piggybacking", "sms"],
                          "sourceAppId": 1159,
                          "bridgeInitiateData": {
                            "apnsTopic": "com.apple.idmsauthwidget",
                            "apnsEnvironment": "prod",
                            "webSocketUrl": "websocket.push.apple.com",
                            "phoneNumberVerification": {
                              "trustedPhoneNumber": {"id": 3, "pushMode": "sms"}
                            }
                          }
                        }
                      }
                    }
                  </script>
                </html>
                """.trimIndent(),
            ),
        )

        assertTrue(context.supportsTrustedDeviceBridge)
        assertEquals(listOf("web_piggybacking", "sms"), context.authFactors)
        assertEquals("1159", context.sourceAppId)
        assertEquals(
            "websocket.push.apple.com",
            context.bridgeInitiateData["webSocketUrl"].toString().trim('"'),
        )
        assertEquals(
            "3",
            context.phoneNumberVerification["trustedPhoneNumber"]
                .toString()
                .substringAfter("\"id\":")
                .substringBefore(','),
        )
    }

    @Test
    fun `malformed HTML does not invent auth options`() {
        assertEquals(null, Hsa2BootContext.parse("<html><script>not json</script></html>"))
    }
}
