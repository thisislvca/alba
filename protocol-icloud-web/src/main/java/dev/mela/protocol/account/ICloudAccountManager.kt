package dev.mela.protocol.account

import dev.mela.protocol.auth.AppleAuthenticationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ICloudAccountManager(
    private val authentication: AppleAuthenticationService,
    private val sessionStore: AppleSessionStore,
    private val telemetry: AccountTelemetry = AccountTelemetry.None,
    private val onLocalSignOut: suspend () -> Unit = {},
) {
    private val operations = Mutex()
    private val mutex = Mutex()

    private suspend fun <T> accountOperation(block: suspend () -> T): T =
        operations.withLock { mutex.withLock { block() } }
    private val mutableState = MutableStateFlow<ICloudAccountState>(ICloudAccountState.Restoring)
    private val mutableSession = MutableStateFlow<AppleSessionSnapshot?>(null)

    val state: StateFlow<ICloudAccountState> = mutableState.asStateFlow()
    val session: StateFlow<AppleSessionSnapshot?> = mutableSession.asStateFlow()

    val authorizedSession: AppleSessionSnapshot?
        get() = mutableSession.value.takeIf {
            (mutableState.value as? ICloudAccountState.SignedIn)?.status == SessionStatus.VERIFIED
        }

    suspend fun restore(force: Boolean = false) = accountOperation {
        val started = System.nanoTime()
        val current = mutableState.value
        if (!force && current != ICloudAccountState.Restoring &&
            (current !is ICloudAccountState.SignedIn || current.status == SessionStatus.VERIFIED)) return@accountOperation
        // A storage failure must not silently select the demo source or erase account data.
        val stored = mutableSession.value ?: sessionStore.load()
        if (stored == null) {
            mutableState.value = ICloudAccountState.Demo
            telemetry.record(AccountOperation.RESTORE, AccountOutcome.SUCCESS, elapsedSince(started))
            return@accountOperation
        }
        mutableSession.value = stored
        try {
            val restored = authentication.restore(stored)
            if (restored == null) {
                mutableState.value = ICloudAccountState.SignedIn(stored.accountName, SessionStatus.EXPIRED)
                telemetry.record(
                    AccountOperation.RESTORE,
                    AccountOutcome.EXPIRED,
                    elapsedSince(started),
                    stored.cookieLifetimeMetrics(),
                )
            } else {
                sessionStore.save(restored)
                mutableSession.value = restored
                val status = restored.sessionStatus()
                mutableState.value = ICloudAccountState.SignedIn(restored.accountName, status)
                telemetry.record(
                    AccountOperation.RESTORE,
                    status.accountOutcome(),
                    elapsedSince(started),
                    restored.cookieLifetimeMetrics(),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = ICloudAccountState.SignedIn(stored.accountName, SessionStatus.OFFLINE)
            telemetry.record(
                AccountOperation.RESTORE,
                AccountOutcome.OFFLINE,
                elapsedSince(started),
                stored.cookieLifetimeMetrics(),
            )
        }
    }

    suspend fun signIn(appleId: String, password: String): AppleSignInResult = accountOperation {
        val started = System.nanoTime()
        val previousState = mutableState.value
        mutableState.value = ICloudAccountState.SigningIn(appleId.trim())
        try {
            val result = authentication.signIn(appleId, password, mutableSession.value)
            when (result) {
                is AppleSignInResult.SignedIn -> {
                    sessionStore.save(result.session)
                    mutableSession.value = result.session
                    val status = result.session.sessionStatus()
                    mutableState.value = ICloudAccountState.SignedIn(result.session.accountName, status)
                    telemetry.record(
                        AccountOperation.SIGN_IN,
                        status.accountOutcome(),
                        elapsedSince(started),
                        result.session.cookieLifetimeMetrics(),
                    )
                }

                is AppleSignInResult.RequiresTwoFactor -> {
                    mutableState.value = ICloudAccountState.AwaitingTwoFactor(
                        appleId = result.appleId,
                        delivery = result.delivery,
                        destinationHint = result.destinationHint,
                    )
                    telemetry.record(AccountOperation.SIGN_IN, AccountOutcome.TWO_FACTOR_REQUIRED, elapsedSince(started))
                }
            }
            result
        } catch (error: Throwable) {
            telemetry.record(AccountOperation.SIGN_IN, error.accountOutcome(), elapsedSince(started))
            authentication.cancel()
            mutableState.value = previousState
            throw error
        }
    }

    suspend fun submitTwoFactor(code: String): AppleSessionSnapshot = accountOperation {
        val started = System.nanoTime()
        val awaiting = mutableState.value as? ICloudAccountState.AwaitingTwoFactor
            ?: error("No Apple verification challenge is active")
        val snapshot = try {
            authentication.submitTwoFactor(code)
        } catch (error: Throwable) {
            mutableState.value = awaiting
            throw error
        }
        try {
            sessionStore.save(snapshot)
            mutableSession.value = snapshot
            val status = snapshot.sessionStatus()
            mutableState.value = ICloudAccountState.SignedIn(snapshot.accountName, status)
            telemetry.record(
                AccountOperation.TWO_FACTOR,
                status.accountOutcome(),
                elapsedSince(started),
                snapshot.cookieLifetimeMetrics(),
            )
            snapshot
        } catch (error: Throwable) {
            telemetry.record(AccountOperation.TWO_FACTOR, error.accountOutcome(), elapsedSince(started))
            authentication.cancel()
            mutableState.value = mutableSession.value?.let {
                ICloudAccountState.SignedIn(it.accountName, SessionStatus.OFFLINE)
            } ?: ICloudAccountState.Demo
            throw error
        }
    }

    suspend fun resendTwoFactor() = accountOperation {
        val started = System.nanoTime()
        check(mutableState.value is ICloudAccountState.AwaitingTwoFactor) {
            "No Apple verification challenge is active"
        }
        try {
            authentication.resendTwoFactor()
            telemetry.record(AccountOperation.RESEND_TWO_FACTOR, AccountOutcome.SUCCESS, elapsedSince(started))
        } catch (error: Throwable) {
            telemetry.record(AccountOperation.RESEND_TWO_FACTOR, error.accountOutcome(), elapsedSince(started))
            throw error
        }
    }

    suspend fun signOut() = operations.withLock {
        val started = System.nanoTime()
        // Only local cleanup is non-cancellable. Do not hold the session mutex
        // while draining media work: a finishing request may update its session.
        val snapshot = withContext(NonCancellable) {
            val previous = mutex.withLock {
                val saved = mutableSession.value
                sessionStore.clear()
                mutableSession.value = null
                mutableState.value = ICloudAccountState.Demo
                authentication.cancel()
                saved
            }
            onLocalSignOut()
            previous
        }
        try {
            if (snapshot != null) withTimeoutOrNull(10_000) { authentication.logout(snapshot) }
        } finally {
            authentication.cancel()
            telemetry.record(AccountOperation.SIGN_OUT, AccountOutcome.SUCCESS, elapsedSince(started))
        }
    }

    suspend fun updateSession(snapshot: AppleSessionSnapshot) = mutex.withLock {
        val current = mutableSession.value ?: return@withLock
        if (current.accountName != snapshot.accountName || current.dsid != snapshot.dsid ||
            current.clientId != snapshot.clientId) {
            return@withLock
        }
        sessionStore.save(snapshot)
        mutableSession.value = snapshot
    }

    suspend fun rejectSession(snapshot: AppleSessionSnapshot) = mutex.withLock {
        val current = mutableSession.value ?: return@withLock
        val signedIn = mutableState.value as? ICloudAccountState.SignedIn ?: return@withLock
        if (current.dsid != snapshot.dsid || current.clientId != snapshot.clientId ||
            current.sessionId != snapshot.sessionId || current.sessionToken != snapshot.sessionToken) {
            return@withLock
        }
        mutableState.value = ICloudAccountState.SignedIn(signedIn.appleId, SessionStatus.EXPIRED)
        telemetry.record(
            AccountOperation.SESSION_REJECTED,
            AccountOutcome.EXPIRED,
            0L,
            current.cookieLifetimeMetrics(),
        )
    }

    fun requireSession(): AppleSessionSnapshot = requireNotNull(authorizedSession) {
        "Sign in to iCloud before loading the live library"
    }
}

private fun AppleSessionSnapshot.sessionStatus(): SessionStatus =
    if (webservices.containsKey("ckdatabasews")) SessionStatus.VERIFIED else SessionStatus.PHOTOS_NOT_ENABLED

private fun SessionStatus.accountOutcome(): AccountOutcome =
    if (this == SessionStatus.PHOTOS_NOT_ENABLED) AccountOutcome.PHOTOS_NOT_ENABLED else AccountOutcome.SUCCESS

internal fun Throwable.accountOutcome(): AccountOutcome =
    if (this is dev.mela.protocol.auth.AppleProtocolException) {
        when (error) {
            dev.mela.protocol.auth.AppleProtocolError.INVALID_CREDENTIALS -> AccountOutcome.INVALID_CREDENTIALS
            dev.mela.protocol.auth.AppleProtocolError.INVALID_TWO_FACTOR_CODE -> AccountOutcome.INVALID_CODE
            dev.mela.protocol.auth.AppleProtocolError.SESSION_EXPIRED -> AccountOutcome.EXPIRED
            dev.mela.protocol.auth.AppleProtocolError.TERMS_UPDATE_REQUIRED -> AccountOutcome.TERMS_REQUIRED
            dev.mela.protocol.auth.AppleProtocolError.UNSUPPORTED_TWO_FACTOR -> AccountOutcome.UNSUPPORTED
            dev.mela.protocol.auth.AppleProtocolError.NETWORK -> AccountOutcome.NETWORK_ERROR
            dev.mela.protocol.auth.AppleProtocolError.MALFORMED_RESPONSE,
            dev.mela.protocol.auth.AppleProtocolError.PHOTOS_UNAVAILABLE -> AccountOutcome.MALFORMED_RESPONSE
        }
    } else AccountOutcome.FAILED
