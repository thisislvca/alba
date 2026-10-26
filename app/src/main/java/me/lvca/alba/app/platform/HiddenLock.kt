package me.lvca.alba.app.platform

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Whether Hide and Unhide are offered. Writing `isHidden` is modelled on the favorite write but no open-source client
 * shows it, so it is on only in debug builds until it has been checked against a real account.
 */
internal val HIDE_EDITS_ENABLED: Boolean get() = me.lvca.alba.app.BuildConfig.DEBUG

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

/**
 * While [secure], keeps the screen out of Recents thumbnails, screenshots and screen recordings, so Hidden and its
 * photos never show outside Alba. Leaving Hidden clears it.
 */
@Composable
internal fun HiddenScreenGuard(activity: Activity?, secure: Boolean) {
    DisposableEffect(activity, secure) {
        val window = activity?.window
        if (secure && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(false)
        }
        onDispose {
            if (secure && window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(true)
            }
        }
    }
}
