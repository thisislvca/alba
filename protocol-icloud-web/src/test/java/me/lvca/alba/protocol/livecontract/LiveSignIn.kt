package me.lvca.alba.protocol.livecontract

import kotlinx.coroutines.runBlocking
import me.lvca.alba.protocol.account.AppleSignInResult
import me.lvca.alba.protocol.auth.AppleAuthenticationService
import me.lvca.alba.protocol.network.OkHttpAppleTransport
import kotlin.system.exitProcess

/**
 * Signs in to iCloud once, interactively, and saves the session for the live contract test:
 * `./gradlew :protocol-icloud-web:liveSignIn --console=plain`. The password is read without echo and used only for this
 * sign-in; it is never saved. The session file is written readable only by its owner, in `.live/` (ignored by git)
 * unless `ALBA_LIVE_SESSION_FILE` names another place. Use a test Apple Account where you can.
 */
fun main() {
    val console = System.console()
    if (console == null) {
        System.err.println("liveSignIn needs an interactive terminal. Run it with --console=plain from a shell.")
        exitProcess(2)
    }
    val file = LiveSession.sessionFile()
    val appleId = console.readLine("Apple Account email: ")?.trim().orEmpty()
    val password = console.readPassword("Password (not shown, not saved): ")?.let(::String).orEmpty()
    if (appleId.isEmpty() || password.isEmpty()) exitProcess(2)
    val service = AppleAuthenticationService(OkHttpAppleTransport())
    val session = runBlocking {
        when (val result = service.signIn(appleId, password)) {
            is AppleSignInResult.SignedIn -> result.session
            is AppleSignInResult.RequiresTwoFactor -> {
                var challenge = result
                var code = console.readLine(
                    "Verification code sent to ${challenge.destinationHint ?: "your trusted devices"}" +
                        (if (challenge.smsAvailable) " (or type sms for a text message)" else "") + ": ",
                )?.trim().orEmpty()
                if (code.equals("sms", ignoreCase = true) && challenge.smsAvailable) {
                    challenge = service.sendCodeBySms()
                    code = console.readLine("Code sent to ${challenge.destinationHint ?: "your phone"}: ")?.trim().orEmpty()
                }
                service.submitTwoFactor(code)
            }
        }
    }
    LiveSession.save(file, session)
    println("Saved the iCloud session to $file. Run ./gradlew :protocol-icloud-web:liveContractTest to check the protocol.")
}
