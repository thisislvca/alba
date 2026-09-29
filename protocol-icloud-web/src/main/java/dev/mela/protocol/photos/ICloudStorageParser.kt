package dev.mela.protocol.photos

import dev.mela.engine.model.CloudStorageUsage
import dev.mela.engine.model.StorageCategory
import kotlinx.serialization.json.*

internal object ICloudStorageParser {
    fun parse(body: String, now: Long = System.currentTimeMillis()): CloudStorageUsage {
        val root = Json.parseToJsonElement(body).jsonObject
        val usage = root["storageUsageInfo"]?.jsonObject ?: error("Apple did not return storage information.")
        val used = usage["usedStorageInBytes"]?.jsonPrimitive?.longOrNull
        val total = usage["totalStorageInBytes"]?.jsonPrimitive?.longOrNull
        check(used != null && used >= 0 && total != null && total > 0) { "Apple returned incomplete storage information." }
        val categories = (root["storageUsageByMedia"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val name = item["displayLabel"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val bytes = item["usageInBytes"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            if (name.isBlank() || bytes < 0) null else StorageCategory(name, bytes)
        }
        return CloudStorageUsage(used, total, categories, now)
    }
}
