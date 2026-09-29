package dev.mela.protocol.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

internal data class Hsa2BootContext(
    val authInitialRoute: String = "",
    val hasTrustedDevices: Boolean = false,
    val authFactors: List<String> = emptyList(),
    val bridgeInitiateData: JsonObject = JsonObject(emptyMap()),
    val phoneNumberVerification: JsonObject = JsonObject(emptyMap()),
    val sourceAppId: String? = null,
) {
    val supportsTrustedDeviceBridge: Boolean
        get() = authInitialRoute == "auth/bridge/step" &&
            hasTrustedDevices &&
            bridgeInitiateData.isNotEmpty()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val scriptPattern = Regex(
            pattern = "<script\\b([^>]*)>([\\s\\S]*?)</script\\s*>",
            option = RegexOption.IGNORE_CASE,
        )
        private val classPattern = Regex(
            pattern = "\\bclass\\s*=\\s*([\"'])(.*?)\\1",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        fun parse(responseBody: String): Hsa2BootContext? {
            val root = parseObject(responseBody)
                ?: extractBootArgs(responseBody)?.let(::parseObject)
                ?: return null
            return fromRoot(root)
        }

        private fun extractBootArgs(html: String): String? = scriptPattern.findAll(html)
            .firstOrNull { match ->
                val classes = classPattern.find(match.groupValues[1])
                    ?.groupValues
                    ?.get(2)
                    ?.split(Regex("\\s+"))
                    .orEmpty()
                "boot_args" in classes
            }
            ?.groupValues
            ?.get(2)
            ?.trim()
            ?.takeIf(String::isNotEmpty)

        private fun parseObject(value: String): JsonObject? = runCatching {
            json.parseToJsonElement(value).jsonObject
        }.getOrNull()

        private fun fromRoot(root: JsonObject): Hsa2BootContext {
            val direct = root.objectOrNull("direct") ?: root
            val twoSv = direct.objectOrNull("twoSV") ?: direct
            val bridge = twoSv.objectOrNull("bridgeInitiateData") ?: JsonObject(emptyMap())
            val phoneVerification = twoSv.objectOrNull("phoneNumberVerification")
                ?: bridge.objectOrNull("phoneNumberVerification")
                ?: JsonObject(emptyMap())
            val factors = (twoSv["authFactors"] as? JsonArray)
                .orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

            return Hsa2BootContext(
                authInitialRoute = direct.stringOrNull("authInitialRoute").orEmpty(),
                hasTrustedDevices = direct.booleanOrNull("hasTrustedDevices") ?: false,
                authFactors = factors,
                bridgeInitiateData = bridge,
                phoneNumberVerification = phoneVerification,
                sourceAppId = twoSv["sourceAppId"]
                    ?.let { it as? JsonPrimitive }
                    ?.contentOrNull,
            )
        }

        private fun JsonObject.objectOrNull(name: String): JsonObject? = this[name] as? JsonObject

        private fun JsonObject.stringOrNull(name: String): String? = this[name]
            ?.let { it as? JsonPrimitive }
            ?.contentOrNull

        private fun JsonObject.booleanOrNull(name: String): Boolean? = this[name]
            ?.let { it as? JsonPrimitive }
            ?.booleanOrNull
    }
}
