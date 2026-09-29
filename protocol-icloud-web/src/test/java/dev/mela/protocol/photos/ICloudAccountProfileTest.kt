package dev.mela.protocol.photos

import dev.mela.engine.model.CloudPlanSource
import dev.mela.protocol.account.*
import dev.mela.protocol.network.*
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ICloudAccountProfileTest {
    private val session = AppleSessionSnapshot("alex@example.com", "client", "GB", "session", "token", null,
        "12345", mapOf("account" to "https://p01-setup.icloud.com"), emptyList(), "Alex Example")

    @Test fun parsesPurchasedSharedAndCombinedEntitlementsWithoutGuessingFromQuota() {
        val plan = ICloudAccountProfileService.parsePlan("""{"featureKey":"cloud.storage",
            "summary":{"includedInPlan":true,"limit":2048,"limitUnits":"GIB"},
            "includedWithAccountPurchasedPlan":{"includedInPlan":false},
            "includedWithAppleOnePlan":{"includedInPlan":true},
            "includedWithSharedPlan":{"includedInPlan":true}}""")!!
        assertEquals(setOf(CloudPlanSource.APPLE_ONE, CloudPlanSource.FAMILY), plan.sources)
        assertEquals(2L shl 40, plan.capacityBytes)
        assertNull(ICloudAccountProfileService.parsePlan("""{"featureKey":"cloud.storage","summary":{"limit":200,"limitUnits":"GIB"}}"""))
        assertNull(ICloudAccountProfileService.parsePlan("""{"featureKey":"unknown"}"""))
    }

    @Test fun unknownUnitsAndOverflowDoNotInventAnAllowance() {
        listOf("\"limit\":50,\"limitUnits\":\"UNKNOWN\"", "\"limit\":9223372036854775807,\"limitUnits\":\"GIB\"").forEach { summary ->
            val plan = ICloudAccountProfileService.parsePlan("""{"featureKey":"cloud.storage","summary":{$summary},"includedWithAccountPurchasedPlan":{"includedInPlan":true}}""")!!
            assertEquals(setOf(CloudPlanSource.ICLOUD_PLUS), plan.sources)
            assertNull(plan.capacityBytes)
        }
    }

    @Test fun loadsOnlyOwnPhotoFromDiscoveredAccountService() = runBlocking {
        val transport = Transport()
        val profile = ICloudAccountProfileService(transport).load(session)
        assertEquals("Alex Example", profile.displayName)
        assertArrayEquals(transport.image, profile.photo)
        val url = transport.photoUrl!!.toHttpUrl()
        assertEquals("p01-setup.icloud.com", url.host)
        assertEquals("12345", url.queryParameter("memberId"))
        assertEquals("/setup/web/family/getMemberPhoto", url.encodedPath)
        assertEquals(CloudPlanSource.ICLOUD_PLUS, profile.plan!!.sources.single())
    }

    @Test fun unavailableOrOversizedPhotoDoesNotDiscardPlan() = runBlocking {
        for (size in listOf(10, 2 * 1024 * 1024 + 1)) {
            val transport = Transport().apply { image = ByteArray(size) }
            val profile = ICloudAccountProfileService(transport).load(session)
            assertNull(profile.photo)
            assertNotNull(profile.plan)
        }
        val missing = Transport().apply { status = 403; photoFailure = true }
        val profile = ICloudAccountProfileService(missing).load(session)
        assertNull(profile.photo)
        assertNull(profile.plan)
        assertEquals("Alex Example", profile.displayName)
    }

    @Test fun missingAccountServiceSkipsPhotoAndCancellationPropagates() = runBlocking {
        val transport = Transport()
        ICloudAccountProfileService(transport).load(session.copy(webservices = emptyMap()))
        assertNull(transport.photoUrl)
        transport.cancel = true
        try {
            ICloudAccountProfileService(transport).load(session)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun profileCannotCrossAccountSwitch() = runBlocking {
        var current = session
        val transport = Transport().apply { onPhoto = { current = session.copy(dsid = "different") } }
        val source = LiveICloudCatalogSource(transport, { current })
        assertTrue(runCatching { source.accountProfile() }.isFailure)
    }

    @Test fun ownCardPhotoRequiresMatchingIdAndTrustedUrl() {
        assertNull(ICloudAccountProfileService.parseOwnPhotoUrl("""{"meCardId":"me","contacts":[{"contactId":"someone-else","photo":{"url":"https://gateway.icloud.com/image"}}]}"""))
        assertEquals("https://gateway.icloud.com/contacts/123/ck/card/photo",
            ICloudAccountProfileService.parseOwnPhotoUrl("""{"meCardId":"me","contacts":[{"contactId":"me","photo":{"url":"https://gateway.icloud.com/contacts/123/ck/card/photo"}}]}"""))
        assertTrue(runCatching { ICloudAccountProfileService.parseOwnPhotoUrl("""{"meCardId":"me","contacts":[{"contactId":"me","photo":{"url":"https://untrusted.example/photo"}}]}""") }.isFailure)
    }

    @Test fun ownCardIsPreferredOverFamilyPhoto() = runBlocking {
        val transport = Transport()
        ICloudAccountProfileService(transport).load(session.copy(webservices = session.webservices + ("contacts" to "https://p01-contactsws.icloud.com")))
        assertEquals("https://gateway.icloud.com/contacts/123/ck/card/photo", transport.photoUrl)
    }

    @Test fun sessionNameRoundTripsAndOldSessionsRemainReadable() {
        assertEquals(session, AppleSessionCodec.decode(AppleSessionCodec.encode(session)))
        val old = AppleSessionCodec.encode(session.copy(displayName = null))
        assertNull(AppleSessionCodec.decode(old).displayName)
    }

    private class Transport : AppleHttpTransport {
        var status = 200
        var image = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()) + ByteArray(12)
        var photoUrl: String? = null
        var photoFailure = false
        var cancel = false
        var onPhoto: () -> Unit = {}
        override val sessionHeaders = AppleSessionHeaders()
        override suspend fun execute(request: AppleHttpRequest): AppleHttpResponse {
            if (cancel) throw CancellationException()
            if (request.url.contains("/co/mecard/")) return AppleHttpResponse(200, emptyMap(),
                """{"meCardId":"me","contacts":[{"contactId":"me","photo":{"url":"https://gateway.icloud.com/contacts/123/ck/card/photo"}}]}""")
            return AppleHttpResponse(status, emptyMap(), """{"featureKey":"cloud.storage","includedWithAccountPurchasedPlan":{"includedInPlan":true}}""")
        }
        override suspend fun stream(url: String, output: OutputStream) {
            photoUrl = url
            onPhoto()
            if (photoFailure) error("Unavailable")
            output.write(image)
        }
        override fun snapshotCookies() = emptyList<PersistedCookie>()
        override fun restore(snapshot: AppleSessionSnapshot) = Unit
        override fun clear() = Unit
    }
}
