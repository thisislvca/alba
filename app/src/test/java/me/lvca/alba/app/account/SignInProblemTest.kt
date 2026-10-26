package me.lvca.alba.app.account

import me.lvca.alba.app.R
import me.lvca.alba.app.messages.UiMessage
import me.lvca.alba.app.messages.toUserMessage
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.TwoFactorDelivery
import me.lvca.alba.protocol.auth.AppleProtocolError
import me.lvca.alba.protocol.auth.AppleProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInProblemTest {
    private fun error(kind: AppleProtocolError) = AppleProtocolException(kind, "test")

    @Test fun fieldErrorsBelongToTheStepThatAskedForTheField() {
        assertEquals(SignInProblem.WRONG_PASSWORD, signInProblem(error(AppleProtocolError.INVALID_CREDENTIALS), codeStep = false))
        assertEquals(SignInProblem.WRONG_CODE, signInProblem(error(AppleProtocolError.INVALID_TWO_FACTOR_CODE), codeStep = true))
        assertNull(signInProblem(error(AppleProtocolError.INVALID_TWO_FACTOR_CODE), codeStep = false))
        assertNull(signInProblem(error(AppleProtocolError.INVALID_CREDENTIALS), codeStep = true))
    }

    @Test fun accountProblemsShowOnTheFormEvenWhenWrapped() {
        assertEquals(SignInProblem.SECURITY_KEY, signInProblem(error(AppleProtocolError.SECURITY_KEY_REQUIRED), codeStep = false))
        assertEquals(SignInProblem.TOO_MANY_ATTEMPTS,
            signInProblem(IllegalStateException("wrapped", error(AppleProtocolError.TOO_MANY_ATTEMPTS)), codeStep = true))
    }

    @Test fun signingOutASavedAccountReturnsToTheIntroductionEvenWhenItsRestoreFailed() {
        assertTrue(signOutRemovesSavedAccount(ICloudAccountState.SignedIn("alex@example.com"), hasSession = true))
        // The restore failed before the session loaded: "Remove saved account" still removes one.
        assertTrue(signOutRemovesSavedAccount(ICloudAccountState.Restoring, hasSession = false))
        // Cancelling a sign-in at the code step leaves the user where they were.
        assertFalse(signOutRemovesSavedAccount(ICloudAccountState.AwaitingTwoFactor("alex@example.com", TwoFactorDelivery.TRUSTED_DEVICE), hasSession = false))
        assertFalse(signOutRemovesSavedAccount(ICloudAccountState.SignedOut, hasSession = false))
    }

    @Test fun otherFailuresStayPassingMessages() {
        assertNull(signInProblem(error(AppleProtocolError.NETWORK), codeStep = false))
        assertNull(signInProblem(IllegalStateException("offline"), codeStep = false))
    }

    @Test fun eachNewAppleErrorHasItsOwnExplanation() {
        mapOf(
            AppleProtocolError.WEB_ACCESS_DISABLED to R.string.web_access_off_message,
            AppleProtocolError.SECURITY_KEY_REQUIRED to R.string.sign_in_error_security_key,
            AppleProtocolError.TOO_MANY_ATTEMPTS to R.string.sign_in_error_too_many_attempts,
        ).forEach { (kind, text) -> assertEquals(UiMessage(text), error(kind).toUserMessage(R.string.malformed_response)) }
    }
}
