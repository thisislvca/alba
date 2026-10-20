package me.lvca.alba.protocol.auth

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.lvca.alba.protocol.account.AccountOperation
import me.lvca.alba.protocol.account.AccountOutcome
import me.lvca.alba.protocol.account.AccountTelemetry
import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.account.accountOutcome
import me.lvca.alba.protocol.account.cookieLifetimeMetrics
import me.lvca.alba.protocol.account.elapsedSince
import me.lvca.alba.protocol.network.AppleClientProfile
import me.lvca.alba.protocol.network.AppleClientProfile.addWebClientBuild
import me.lvca.alba.protocol.network.AppleEndpointPolicy
import me.lvca.alba.protocol.network.AppleHttpRequest
import me.lvca.alba.protocol.network.AppleHttpResponse
import me.lvca.alba.protocol.network.AppleSessionHeaderTracker
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The iCloud.com web session on setup.icloud.com, after Apple ID sign-in: establishing it from the
 * sign-in tokens, validating a saved session, renewing it with those tokens, and ending it.
 */
internal class ICloudWebSession(
    private val transport: AppleSessionHeaderTracker,
    private val telemetry: AccountTelemetry,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Logs in with the tokens the sign-in responses returned. */
    suspend fun establish(appleId: String, clientId: String): AppleSessionSnapshot {
        val response = accountLogin(clientId)
        if (response.code !in 200..299) {
            throw AppleProtocolException(
                AppleProtocolError.SESSION_EXPIRED,
                "Apple did not establish the iCloud web session.",
            )
        }
        return snapshotFromAccountResponse(appleId, clientId, response)
    }

    /** The validated or renewed [snapshot], or null when Apple no longer accepts its tokens. */
    suspend fun restore(snapshot: AppleSessionSnapshot): AppleSessionSnapshot? {
        val started = System.nanoTime()
        transport.restore(snapshot)
        val response = try {
            transport.execute(
                AppleHttpRequest(
                    method = "POST",
                    url = setupUrl("validate", snapshot.clientId, snapshot.dsid),
                    headers = JSON_HEADERS,
                    body = "null",
                ),
            )
        } catch (error: Throwable) {
            telemetry.record(
                AccountOperation.VALIDATE,
                error.accountOutcome(),
                elapsedSince(started),
                snapshot.cookieLifetimeMetrics(),
            )
            throw error
        }
        // A stale validation cookie does not necessarily mean Apple's longer-lived
        // session and trust tokens have expired. Match iCloud.com's token login before
        // asking the person for their password and another verification code.
        if (response.code == 401 || response.code == 403) {
            telemetry.record(
                AccountOperation.VALIDATE,
                AccountOutcome.EXPIRED,
                elapsedSince(started),
                snapshot.cookieLifetimeMetrics(),
            )
            return renew(snapshot)
        }
        check(response.code in 200..299) { "iCloud validation is temporarily unavailable (${response.code})" }
        val restored = snapshotFromAccountResponse(
            appleId = snapshot.accountName,
            clientId = snapshot.clientId,
            response = response,
            fallback = snapshot,
        )
        telemetry.record(
            AccountOperation.VALIDATE,
            AccountOutcome.SUCCESS,
            elapsedSince(started),
            restored.cookieLifetimeMetrics(),
        )
        return restored
    }

    private suspend fun renew(snapshot: AppleSessionSnapshot): AppleSessionSnapshot? {
        val started = System.nanoTime()
        val response = try {
            accountLogin(snapshot.clientId)
        } catch (error: Throwable) {
            telemetry.record(
                AccountOperation.RENEW,
                error.accountOutcome(),
                elapsedSince(started),
                snapshot.cookieLifetimeMetrics(),
            )
            throw error
        }
        if (response.code == 401 || response.code == 403) {
            telemetry.record(
                AccountOperation.RENEW,
                AccountOutcome.EXPIRED,
                elapsedSince(started),
                snapshot.cookieLifetimeMetrics(),
            )
            return null
        }
        check(response.code in 200..299) {
            "iCloud token renewal is temporarily unavailable (${response.code})"
        }
        return try {
            snapshotFromAccountResponse(
                appleId = snapshot.accountName,
                clientId = snapshot.clientId,
                response = response,
                fallback = snapshot,
            ).also {
                telemetry.record(
                    AccountOperation.RENEW,
                    AccountOutcome.SUCCESS,
                    elapsedSince(started),
                    it.cookieLifetimeMetrics(),
                )
            }
        } catch (error: AppleProtocolException) {
            telemetry.record(
                AccountOperation.RENEW,
                error.accountOutcome(),
                elapsedSince(started),
                snapshot.cookieLifetimeMetrics(),
            )
            if (error.error == AppleProtocolError.SESSION_EXPIRED) null else throw error
        }
    }

    /** Ends [snapshot] on Apple's side when it can; the local session is already gone. */
    suspend fun logout(snapshot: AppleSessionSnapshot) {
        transport.restore(snapshot)
        try {
            transport.execute(
                AppleHttpRequest(
                    method = "POST",
                    url = setupUrl("logout", snapshot.clientId, snapshot.dsid),
                    headers = mapOf("Content-Type" to AppleClientProfile.WEB_SERVICE_CONTENT_TYPE),
                    body = buildJsonObject {
                        put("trustBrowser", false)
                        put("allBrowsers", false)
                    }.toString(),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Remote logout is best effort.
        }
    }

    private suspend fun accountLogin(clientId: String): AppleHttpResponse {
        val headers = transport.headers
        val country = headers.accountCountryCode
            ?: throw malformed("Apple did not return an account country")
        val sessionToken = headers.sessionToken
            ?: throw malformed("Apple did not return a session token")
        return transport.execute(
            AppleHttpRequest(
                method = "POST",
                url = setupUrl("accountLogin", clientId),
                headers = JSON_HEADERS,
                body = buildJsonObject {
                    put("accountCountryCode", country)
                    put("dsWebAuthToken", sessionToken)
                    put("extended_login", true)
                    put("trustToken", headers.trustToken.orEmpty())
                }.toString(),
            ),
        )
    }

    private fun snapshotFromAccountResponse(
        appleId: String,
        clientId: String,
        response: AppleHttpResponse,
        fallback: AppleSessionSnapshot? = null,
    ): AppleSessionSnapshot {
        val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: throw malformed("Apple returned an invalid account response")
        if ((root["termsUpdateNeeded"] as? JsonPrimitive)?.booleanOrNull == true) {
            throw AppleProtocolException(
                AppleProtocolError.TERMS_UPDATE_REQUIRED,
                "Open iCloud.com and accept Apple's updated terms, then try again.",
            )
        }
        val dsInfo = root["dsInfo"] as? JsonObject
        val dsid = (dsInfo?.get("dsid") as? JsonPrimitive)?.contentOrNull ?: fallback?.dsid
            ?: throw malformed("Apple did not return an account identifier")
        val trusted = (dsInfo?.get("hsaVersion") as? JsonPrimitive)?.intOrNull != 2 ||
            (root["hsaTrustedBrowser"] as? JsonPrimitive)?.booleanOrNull == true
        if (!trusted) {
            throw AppleProtocolException(
                AppleProtocolError.SESSION_EXPIRED,
                "The Apple session still requires verification.",
            )
        }
        val discoveredServices = root["webservices"] as? JsonObject
        val services = buildMap {
            for (serviceName in DISCOVERED_SERVICES) {
                val discoveredUrl = (discoveredServices?.get(serviceName) as? JsonObject)
                    ?.get("url")
                    ?.let { it as? JsonPrimitive }
                    ?.contentOrNull
                // A fresh inventory is authoritative: do not resurrect withdrawn services.
                val url = if (discoveredServices != null) discoveredUrl else fallback?.webservices?.get(serviceName)
                url?.also(AppleEndpointPolicy::requireAllowed)?.trimEnd('/')?.let { put(serviceName, it) }
            }
        }
        services.values.forEach(AppleEndpointPolicy::requireAllowed)
        val sessionHeaders = transport.headers
        return AppleSessionSnapshot(
            accountName = appleId,
            displayName = (dsInfo?.get("fullName") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: listOfNotNull("firstName", "lastName").mapNotNull { key ->
                    (dsInfo?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                }.joinToString(" ").takeIf { it.isNotBlank() }
                ?: fallback?.takeIf { it.dsid == dsid }?.displayName,
            clientId = clientId,
            accountCountryCode = sessionHeaders.accountCountryCode ?: fallback?.accountCountryCode
                ?: throw malformed("Apple did not return an account country"),
            sessionId = sessionHeaders.sessionId ?: fallback?.sessionId
                ?: throw malformed("Apple did not return a session identifier"),
            sessionToken = sessionHeaders.sessionToken ?: fallback?.sessionToken
                ?: throw malformed("Apple did not return a session token"),
            trustToken = sessionHeaders.trustToken ?: fallback?.trustToken,
            dsid = dsid,
            webservices = services,
            cookies = transport.snapshotCookies(),
        )
    }

    private fun setupUrl(path: String, clientId: String, dsid: String? = null): String =
        "$SETUP_ENDPOINT/$path".toHttpUrl().newBuilder()
            .addWebClientBuild()
            .addQueryParameter("clientId", clientId)
            .apply { dsid?.let { addQueryParameter("dsid", it) } }
            .build()
            .toString()

    private fun malformed(message: String) = AppleProtocolException(AppleProtocolError.MALFORMED_RESPONSE, message)

    private companion object {
        val DISCOVERED_SERVICES = listOf("ckdatabasews", "photosupload", "account", "contacts")
        const val SETUP_ENDPOINT = "https://setup.icloud.com/setup/ws/1"
        val JSON_HEADERS = mapOf("Content-Type" to "application/json")
    }
}
