package me.lvca.alba.app.platform

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

/** What came of asking the user to unlock Hidden. */
internal enum class UnlockResult { UNLOCKED, CANCELED, NO_SCREEN_LOCK, FAILED }

/**
 * Locks iCloud's Hidden album behind the phone's own unlock, as iOS does with Face ID: a fingerprint or face, or the
 * screen lock PIN, pattern or password. Without a screen lock Hidden stays closed, so a borrowed phone never shows it.
 */
internal object HiddenLock {
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun unlock(context: Context, title: String, subtitle: String, done: (UnlockResult) -> Unit) {
        val manager = context.getSystemService(BiometricManager::class.java)
        if (manager?.canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
            done(UnlockResult.NO_SCREEN_LOCK)
            return
        }
        val prompt = BiometricPrompt.Builder(context)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setConfirmationRequired(false)
            .build()
        runCatching {
            prompt.authenticate(CancellationSignal(), context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(UnlockResult.UNLOCKED)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    done(if (errorCode in CANCELED) UnlockResult.CANCELED else UnlockResult.FAILED)
                }
            })
        }.onFailure { done(UnlockResult.FAILED) }
    }

    private val CANCELED = setOf(BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED)
}
