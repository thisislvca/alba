package dev.mela.protocol.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object AppleSessionCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(snapshot: AppleSessionSnapshot): String = buildJsonObject {
        put("version", FORMAT_VERSION)
        put("accountName", snapshot.accountName)
        snapshot.displayName?.let { put("displayName", it) }
        put("clientId", snapshot.clientId)
        put("accountCountryCode", snapshot.accountCountryCode)
        put("sessionId", snapshot.sessionId)
        put("sessionToken", snapshot.sessionToken)
        snapshot.trustToken?.let { put("trustToken", it) }
        put("dsid", snapshot.dsid)
        putJsonObject("webservices") {
            snapshot.webservices.toSortedMap().forEach { (name, url) -> put(name, url) }
        }
        putJsonArray("cookies") {
            snapshot.cookies.forEach { cookie ->
                add(
                    buildJsonObject {
                        put("name", cookie.name)
                        put("value", cookie.value)
                        put("domain", cookie.domain)
                        put("path", cookie.path)
                        cookie.expiresAtEpochMillis?.let { put("expiresAt", it) }
                        put("secure", cookie.secure)
                        put("httpOnly", cookie.httpOnly)
                        put("hostOnly", cookie.hostOnly)
                    },
                )
            }
        }
    }.toString()

    fun decode(value: String): AppleSessionSnapshot {
        val root = json.parseToJsonElement(value).jsonObject
        require(root.requiredLong("version") == FORMAT_VERSION.toLong()) {
            "Unsupported encrypted session format"
        }
        return AppleSessionSnapshot(
            accountName = root.requiredString("accountName"),
            displayName = root["displayName"]?.jsonPrimitive?.contentOrNull,
            clientId = root.requiredString("clientId"),
            accountCountryCode = root.requiredString("accountCountryCode"),
            sessionId = root.requiredString("sessionId"),
            sessionToken = root.requiredString("sessionToken"),
            trustToken = root["trustToken"]?.jsonPrimitive?.contentOrNull,
            dsid = root.requiredString("dsid"),
            webservices = root["webservices"]
                ?.jsonObject
                ?.mapValues { (_, url) -> url.jsonPrimitive.content }
                .orEmpty(),
            cookies = root["cookies"]
                ?.jsonArray
                ?.map { element ->
                    val cookie = element.jsonObject
                    PersistedCookie(
                        name = cookie.requiredString("name"),
                        value = cookie.requiredString("value"),
                        domain = cookie.requiredString("domain"),
                        path = cookie.requiredString("path"),
                        expiresAtEpochMillis = cookie["expiresAt"]?.jsonPrimitive?.longOrNull,
                        secure = cookie.requiredBoolean("secure"),
                        httpOnly = cookie.requiredBoolean("httpOnly"),
                        hostOnly = cookie.requiredBoolean("hostOnly"),
                    )
                }
                .orEmpty(),
        )
    }

    private fun JsonObject.requiredString(name: String): String = requireNotNull(
        this[name]?.jsonPrimitive?.contentOrNull,
    ) { "Encrypted session is missing $name" }

    private fun JsonObject.requiredLong(name: String): Long = requireNotNull(
        this[name]?.jsonPrimitive?.longOrNull,
    ) { "Encrypted session is missing $name" }

    private fun JsonObject.requiredBoolean(name: String): Boolean = requireNotNull(
        this[name]?.jsonPrimitive,
    ) { "Encrypted session is missing $name" }.boolean

    private const val FORMAT_VERSION = 1
}
