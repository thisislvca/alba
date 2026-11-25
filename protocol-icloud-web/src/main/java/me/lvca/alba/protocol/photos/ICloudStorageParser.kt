package me.lvca.alba.protocol.photos

import kotlinx.serialization.json.*
import me.lvca.alba.engine.model.CloudStorageUsage
import me.lvca.alba.engine.model.StorageCategory
import java.util.Locale

internal object ICloudStorageParser {
    fun parse(body: String, now: Long = System.currentTimeMillis()): CloudStorageUsage {
        val root = Json.parseToJsonElement(body).jsonObject
        val usage = root["storageUsageInfo"]?.jsonObject ?: error("Apple did not return storage information.")
        val used = usage["usedStorageInBytes"]?.jsonPrimitive?.longOrNull
        val total = usage["totalStorageInBytes"]?.jsonPrimitive?.longOrNull
        check(used != null && used >= 0 && total != null && total > 0) { "Apple returned incomplete storage information." }
        var familyInMedia: Long? = null
        val categories = (root["storageUsageByMedia"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val name = item["displayLabel"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val bytes = item["usageInBytes"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            when {
                name.isBlank() || bytes < 0 -> null
                // The family's share is its own segment, never one of the account's categories.
                item.isFamily(name) -> { familyInMedia = bytes; null }
                else -> StorageCategory(name, bytes)
            }
        }
        // The rest of a Family Sharing group's usage of a shared plan: part of usedStorageInBytes, not of the account's media.
        val familyInfo = (root["familyStorageUsageInfo"] ?: usage["familyStorageUsageInfo"]) as? JsonObject
        val family = familyInfo?.get("usageInBytes")?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 } ?: familyInMedia ?: 0
        return CloudStorageUsage(used, total, categories, now, familyBytes = family)
    }

    private fun JsonObject.isFamily(label: String): Boolean =
        this["mediaKey"]?.jsonPrimitive?.contentOrNull?.lowercase(Locale.ROOT) == "family" ||
            label.trim().lowercase(Locale.ROOT) in setOf("family", "family sharing")
}
