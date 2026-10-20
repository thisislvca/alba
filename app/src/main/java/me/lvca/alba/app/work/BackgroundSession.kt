package me.lvca.alba.app.work

import me.lvca.alba.protocol.account.ICloudAccountManager
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus

internal enum class BackgroundSessionDecision { RUN, RETRY, PAUSE }

internal fun backgroundSessionDecision(
    state: ICloudAccountState,
    hasSavedSession: Boolean,
): BackgroundSessionDecision = when {
    !hasSavedSession -> BackgroundSessionDecision.RUN
    state is ICloudAccountState.SignedIn && state.status == SessionStatus.VERIFIED ->
        BackgroundSessionDecision.RUN
    state is ICloudAccountState.SignedIn && state.status == SessionStatus.EXPIRED ->
        BackgroundSessionDecision.PAUSE
    state is ICloudAccountState.SignedIn && state.status == SessionStatus.PHOTOS_NOT_ENABLED ->
        BackgroundSessionDecision.PAUSE
    else -> BackgroundSessionDecision.RETRY
}

/** Decides from this account manager's current state and saved session. */
internal fun ICloudAccountManager.backgroundSessionDecision(): BackgroundSessionDecision =
    backgroundSessionDecision(state = state.value, hasSavedSession = session.value != null)
