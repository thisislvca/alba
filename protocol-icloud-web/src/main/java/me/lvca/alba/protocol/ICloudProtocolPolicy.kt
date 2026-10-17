package me.lvca.alba.protocol

object ICloudProtocolPolicy {
    const val liveTrafficEnabled: Boolean = true
    const val buildModeLabel: String = "Demo or direct iCloud"

    fun requireLiveTrafficAuthorization() = Unit
}
