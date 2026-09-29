package dev.mela.protocol.account

sealed interface ICloudAccountState {
    data object Demo : ICloudAccountState

    data object Restoring : ICloudAccountState

    data class SigningIn(
        val appleId: String,
    ) : ICloudAccountState

    data class AwaitingTwoFactor(
        val appleId: String,
        val delivery: TwoFactorDelivery,
        val destinationHint: String? = null,
    ) : ICloudAccountState

    data class SignedIn(
        val appleId: String,
        val status: SessionStatus = SessionStatus.VERIFIED,
    ) : ICloudAccountState
}

enum class TwoFactorDelivery {
    TRUSTED_DEVICE,
    SMS,
}

sealed interface AppleSignInResult {
    data class SignedIn(
        val session: AppleSessionSnapshot,
    ) : AppleSignInResult

    data class RequiresTwoFactor(
        val appleId: String,
        val delivery: TwoFactorDelivery,
        val destinationHint: String? = null,
    ) : AppleSignInResult
}

enum class SessionStatus { VERIFIED, PHOTOS_NOT_ENABLED, OFFLINE, EXPIRED }
