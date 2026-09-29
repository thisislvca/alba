package dev.mela.protocol.photos

import dev.mela.engine.model.CloudAccountProfile
import dev.mela.engine.model.CloudPlanSource
import dev.mela.engine.model.CloudStoragePlan
import dev.mela.protocol.account.AppleSessionSnapshot
import dev.mela.protocol.network.AppleHttpRequest
import dev.mela.protocol.network.AppleHttpTransport
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Read-only endpoints also used by pyicloud's AccountService; no family inventory is requested. */
internal class ICloudAccountProfileService(private val transport: AppleHttpTransport) {
    suspend fun load(session: AppleSessionSnapshot): CloudAccountProfile = coroutineScope {
        val suffix = if (session.accountCountryCode.equals("CN", ignoreCase = true)) ".cn" else ""
        fun url(base: String) = base.toHttpUrl().newBuilder()
            .addQueryParameter("clientBuildNumber", "2534Project66")
            .addQueryParameter("clientMasteringNumber", "2534B22")
            .addQueryParameter("clientId", session.clientId)
            .addQueryParameter("dsid", session.dsid)
        val plan = async { optional {
            val endpoint = "https://gatewayws.icloud.com$suffix/acsegateway/v3/accounts".toHttpUrl().newBuilder()
                .addPathSegment(session.dsid)
                .addPathSegments("subscriptions/features/cloud.storage/plan-summary").build()
            val response = transport.execute(AppleHttpRequest("GET", url(endpoint.toString()).build().toString()))
            // An unavailable optional account service must not invalidate a working Photos session.
            if (response.code in 200..299) parsePlan(response.body) else null
        } }
        val photo = async { optional {
            // iCloud.com uses the signed-in user's own contact card, not the address book.
            val ownPhoto = optional photoLookup@ {
                val contactsRoot = session.webservices["contacts"] ?: return@photoLookup null
                val response = transport.execute(AppleHttpRequest("GET",
                    url("${contactsRoot.trimEnd('/')}/co/mecard/").build().toString()))
                if (response.code in 200..299) parseOwnPhotoUrl(response.body) else null
            }
            val endpoint = ownPhoto ?: session.webservices["account"]?.let { accountRoot ->
                url("${accountRoot.trimEnd('/')}/setup/web/family/getMemberPhoto")
                    .addQueryParameter("memberId", session.dsid).build().toString()
            } ?: return@optional null
            val output = object : ByteArrayOutputStream() {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    check(count.toLong() + len <= MAX_PHOTO_BYTES) { "Account photo is too large" }
                    super.write(b, off, len)
                }
                override fun write(b: Int) {
                    check(count < MAX_PHOTO_BYTES) { "Account photo is too large" }
                    super.write(b)
                }
            }
            transport.stream(endpoint, output)
            output.toByteArray().takeIf(::isPhoto)
        } }
        CloudAccountProfile(session.displayName, photo.await(), plan.await())
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = withTimeoutOrNull(8_000) {
        try { block() } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
    }

    companion object {
        private const val MAX_PHOTO_BYTES = 2 * 1024 * 1024
        private fun isPhoto(bytes: ByteArray): Boolean =
            bytes.size >= 12 && (
                (bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) ||
                bytes.take(8).toByteArray().contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)) ||
                (String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"))

        internal fun parseOwnPhotoUrl(body: String): String? {
            val root = Json.parseToJsonElement(body) as? JsonObject ?: return null
            val meCardId = (root["meCardId"] as? JsonPrimitive)?.contentOrNull ?: return null
            val ownCard = (root["contacts"] as? JsonArray)?.filterIsInstance<JsonObject>()?.firstOrNull {
                (it["contactId"] as? JsonPrimitive)?.contentOrNull == meCardId
            } ?: return null
            val url = ((ownCard["photo"] as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull ?: return null
            // Do not let an unexpected contact image URL send an authenticated request off Apple.
            return dev.mela.protocol.network.AppleEndpointPolicy.requireAllowed(url).toString()
        }

        internal fun parsePlan(body: String): CloudStoragePlan? {
            val root = Json.parseToJsonElement(body) as? JsonObject ?: return null
            if ((root["featureKey"] as? JsonPrimitive)?.contentOrNull != "cloud.storage") return null
            val keys = mapOf(
                "includedWithAccountPurchasedPlan" to CloudPlanSource.ICLOUD_PLUS,
                "includedWithAppleOnePlan" to CloudPlanSource.APPLE_ONE,
                "includedWithSharedPlan" to CloudPlanSource.FAMILY,
                "includedWithCompedPlan" to CloudPlanSource.COMPLIMENTARY,
                "includedWithManagedPlan" to CloudPlanSource.MANAGED,
            )
            val sources = keys.mapNotNull { (key, source) ->
                source.takeIf { ((root[key] as? JsonObject)?.get("includedInPlan") as? JsonPrimitive)?.booleanOrNull == true }
            }.toSet()
            if (sources.isEmpty()) return null
            val summary = root["summary"] as? JsonObject
            val limit = (summary?.get("limit") as? JsonPrimitive)?.longOrNull
            val unit = (summary?.get("limitUnits") as? JsonPrimitive)?.contentOrNull
            val multiplier = when (unit) { "GIB" -> 1L shl 30; "TIB" -> 1L shl 40; else -> null }
            val capacity = if (limit != null && limit > 0 && multiplier != null && limit <= Long.MAX_VALUE / multiplier)
                limit * multiplier else null
            return CloudStoragePlan(sources, capacity)
        }
    }
}
