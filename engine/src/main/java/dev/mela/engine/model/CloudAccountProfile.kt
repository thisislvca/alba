package dev.mela.engine.model

/** Optional account details. Photo bytes stay in memory and are never added to the media cache. */
data class CloudAccountProfile(
    val displayName: String? = null,
    val photo: ByteArray? = null,
    val plan: CloudStoragePlan? = null,
)

data class CloudStoragePlan(val sources: Set<CloudPlanSource>, val capacityBytes: Long?)
enum class CloudPlanSource { ICLOUD_PLUS, APPLE_ONE, FAMILY, COMPLIMENTARY, MANAGED }
