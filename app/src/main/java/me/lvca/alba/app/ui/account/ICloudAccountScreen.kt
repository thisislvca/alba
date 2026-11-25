package me.lvca.alba.app.ui.account

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.DiagnosticsConsent
import me.lvca.alba.app.R
import me.lvca.alba.app.account.PhoneStorageState
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.account.SignInProblem
import me.lvca.alba.app.transfers.TransferState
import me.lvca.alba.app.ui.AccountScreenActions
import me.lvca.alba.app.ui.designsystem.AlbaCard
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.AlbaPageScaffold
import me.lvca.alba.app.ui.designsystem.theme.AlbaIconSize
import me.lvca.alba.app.ui.navigation.BackPhase
import me.lvca.alba.app.ui.navigation.NavigationDirection
import me.lvca.alba.app.ui.navigation.NavigationMotion
import me.lvca.alba.app.ui.navigation.PredictiveBackNavigation
import me.lvca.alba.app.ui.navigation.PredictiveBackState
import me.lvca.alba.app.ui.navigation.navigationTransform
import me.lvca.alba.app.ui.navigation.predictiveBackPose
import me.lvca.alba.app.ui.navigation.rememberNavigationTransition
import me.lvca.alba.app.ui.viewer.reduceGalleryMotion
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus
import me.lvca.alba.protocol.account.TwoFactorDelivery

@Composable
fun ICloudAccountScreen(
    session: SessionState,
    transfers: TransferState,
    phoneStorage: PhoneStorageState,
    mediaBusy: Boolean,
    diagnostics: DiagnosticsConsent,
    actions: AccountScreenActions,
    /** Leaves sign-in for the sample library; null where it isn't offered (release builds, or with an account). */
    tryDemo: (() -> Unit)? = null,
) {
    val state = session.accountState
    var page by rememberSaveable { mutableStateOf(ProfilePage.OVERVIEW) }
    var appleId by rememberSaveable { mutableStateOf("") }
    // The password lives in the view model's memory (see SignInDraft): it survives rotation but never saved state.
    // A verification code is short-lived and single-use, so saved state is safe for it.
    val draft = actions.signInDraft
    var verificationCode by rememberSaveable { mutableStateOf("") }
    val signedIn = state as? ICloudAccountState.SignedIn
    LaunchedEffect(state) {
        if (signedIn?.status == SessionStatus.VERIFIED && page == ProfilePage.SIGN_IN) page = ProfilePage.OVERVIEW
    }
    // A code belongs to one challenge: leaving the code step, either way, drops it.
    LaunchedEffect(state is ICloudAccountState.AwaitingTwoFactor) {
        if (state !is ICloudAccountState.AwaitingTwoFactor) verificationCode = ""
    }
    LaunchedEffect(page) {
        if (page == ProfilePage.OVERVIEW) actions.checkPhoneStorage()
    }
    // A notification or the gallery asked for a page, such as Backup.
    LaunchedEffect(session.requestedPage) {
        session.requestedPage?.let { requested ->
            page = requested
            actions.accountPageShown()
        }
    }
    val back = {
        if (page == ProfilePage.OVERVIEW) actions.closeAccount() else {
            page = page.parent
            draft.password = ""
            verificationCode = ""
            actions.clearSignInProblem()
        }
    }
    val reduceMotion = reduceGalleryMotion()
    val pageBack = remember { PredictiveBackState() }
    val (pageState, pageTransition) = rememberNavigationTransition(page, pageBack, reduceMotion, label = "account-pages")
    PredictiveBackNavigation(pageState, pageBack, previous = page.parent.takeIf { page != ProfilePage.OVERVIEW },
        key = { it }, reduceMotion = reduceMotion) { back() }
    val slide = with(LocalDensity.current) { NavigationMotion.SHARED_AXIS_DP.dp.roundToPx() }
    pageTransition.AnimatedContent(
        transitionSpec = {
            val direction = when {
                targetState == ProfilePage.OVERVIEW || targetState == initialState.parent -> NavigationDirection.BACK
                initialState == ProfilePage.OVERVIEW || initialState == targetState.parent -> NavigationDirection.FORWARD
                else -> NavigationDirection.LATERAL
            }
            navigationTransform(direction, slide, gesture = pageBack.phase != BackPhase.IDLE, reduceMotion = reduceMotion)
        },
        modifier = Modifier.fillMaxSize(),
    ) { shown ->
        Box(Modifier.fillMaxSize().predictiveBackPose(pageBack, shown)) {
            AccountPage(shown, session, transfers, phoneStorage, mediaBusy, diagnostics, actions,
                appleId = appleId, password = draft.password, verificationCode = verificationCode,
                onAppleId = { appleId = it; actions.clearSignInProblem() },
                onPassword = { draft.password = it; actions.clearSignInProblem() },
                onVerificationCode = { verificationCode = it; actions.clearSignInProblem() },
                onNavigate = { page = it }, onBack = back, tryDemo = tryDemo)
        }
    }
}

/** One page of the account screen; [page] is the page being shown, which differs from the current one while it animates. */
@Composable
private fun AccountPage(
    page: ProfilePage,
    session: SessionState,
    transfers: TransferState,
    phoneStorage: PhoneStorageState,
    mediaBusy: Boolean,
    diagnostics: DiagnosticsConsent,
    actions: AccountScreenActions,
    appleId: String,
    password: String,
    verificationCode: String,
    onAppleId: (String) -> Unit,
    onPassword: (String) -> Unit,
    onVerificationCode: (String) -> Unit,
    onNavigate: (ProfilePage) -> Unit,
    onBack: () -> Unit,
    tryDemo: (() -> Unit)?,
) {
    val state = session.accountState
    val busy = session.accountBusy
    val signedIn = state as? ICloudAccountState.SignedIn
    if (page == ProfilePage.ABOUT) {
        AboutScreen(diagnostics, actions::setDiagnosticsEnabled, onBack)
        return
    }
    val signingIn = state is ICloudAccountState.SigningIn || state is ICloudAccountState.AwaitingTwoFactor
    if (signingIn || page == ProfilePage.SIGN_IN || state == ICloudAccountState.SignedOut) {
        val shownAppleId = when (state) {
            is ICloudAccountState.SigningIn -> appleId.ifBlank { state.appleId }
            else -> appleId.ifBlank { signedIn?.appleId.orEmpty() }
        }
        SignInFlow(
            SignInFlowState(
                challenge = state as? ICloudAccountState.AwaitingTwoFactor,
                busy = busy || state is ICloudAccountState.SigningIn,
                expired = signedIn?.status == SessionStatus.EXPIRED,
                appleId = shownAppleId, password = password, code = verificationCode, problem = session.signInProblem,
            ),
            SignInFlowActions(
                onAppleIdChange = onAppleId, onPasswordChange = onPassword, onCodeChange = onVerificationCode,
                onSignIn = { actions.signIn(shownAppleId, password) },
                onSubmitCode = actions::submitTwoFactor,
                onResendCode = actions::resendTwoFactor, onSendCodeBySms = actions::sendCodeBySms,
                onCancelCode = { onVerificationCode(""); actions.cancelTwoFactor() },
                onTryDemo = tryDemo,
            ),
        )
        return
    }
    AlbaPageScaffold(
        title = stringResource(if (state == ICloudAccountState.Restoring) R.string.icloud_account else page.title),
        onBack = onBack,
    ) {
        when {
            state == ICloudAccountState.Restoring -> if (session.restoreFailed) {
                Text(stringResource(R.string.the_saved_account_could_not_be_opened_your_library_has_not_been_replaced))
                Button(onClick = actions::retryRestore) { Text(stringResource(R.string.retry_saved_account)) }
                ConfirmSignOutButton(R.string.remove_saved_account_confirm_title, R.string.remove_saved_account_confirm_action,
                    onSignOut = actions::signOut) { onClick ->
                    OutlinedButton(onClick = onClick, modifier = Modifier.testTag("remove-saved-account")) {
                        Text(stringResource(R.string.remove_saved_account_and_use_demo))
                    }
                }
            } else ProgressState(
                stringResource(R.string.restoring_your_session),
                stringResource(R.string.checking_the_encrypted_icloud_session_stored_on_this_phone),
            )
            page == ProfilePage.BACKUP -> BackupPage(session, transfers, actions, onNavigate)
            page == ProfilePage.BACKUP_FOLDERS -> DeviceFoldersPage(transfers, actions)
            page == ProfilePage.FREE_UP_SPACE -> FreeUpSpacePage(transfers, actions)
            page == ProfilePage.PHONE -> PhoneStorageDetails(phoneStorage, busy = mediaBusy || busy, actions)
            page == ProfilePage.ACTIVATION && signedIn != null -> PhotosNotEnabledAccount(
                signedIn.appleId, session.accountInfo.checking || busy, actions::checkConnection, actions::signOut,
            )
            else -> ProfileOverview(
                session, phoneStorage, transfers.backup,
                onNavigate = onNavigate, onCheckConnection = actions::checkConnection, onSignOut = actions::signOut,
            )
        }
    }
}

@Composable
private fun PhotosNotEnabledAccount(
    appleId: String,
    checking: Boolean,
    onCheckConnection: () -> Unit,
    onSignOut: () -> Unit,
) {
    AlbaCard(color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(AlbaIcons.Info, contentDescription = null, modifier = Modifier.size(AlbaIconSize.large))
            Text(stringResource(R.string.icloud_photos_is_not_active), style = MaterialTheme.typography.titleLarge)
            Text(appleId, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.icloud_web_only_explanation),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
    Spacer(Modifier.height(22.dp))
    Text(stringResource(R.string.how_to_activate_icloud_photos), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.activate_icloud_photos_steps),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.icloud_plus_not_required),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(22.dp))
    Button(onClick = onCheckConnection, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
        Text(if (checking) stringResource(R.string.checking) else stringResource(R.string.check_again))
    }
    ConfirmSignOutButton(onSignOut = onSignOut) { onClick ->
        OutlinedButton(onClick = onClick, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sign_out_and_use_demo_library))
        }
    }
}

/** Problems that are about the account rather than one field: an explanation and, where Apple has one, its help page. */
@Composable
internal fun SignInProblemNotice(problem: SignInProblem?) {
    val (message, help) = when (problem) {
        SignInProblem.SECURITY_KEY -> R.string.sign_in_error_security_key to (R.string.sign_in_help_security_keys to SECURITY_KEYS_URL)
        SignInProblem.TOO_MANY_ATTEMPTS -> R.string.sign_in_error_too_many_attempts to (R.string.sign_in_help_unlock to FORGOT_PASSWORD_URL)
        SignInProblem.WRONG_PASSWORD, SignInProblem.WRONG_CODE, null -> return
    }
    Spacer(Modifier.height(12.dp))
    AlbaCard(Modifier.testTag("sign-in-problem"), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(stringResource(message), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer)
            HelpLink(help.first, help.second)
        }
    }
}

/** A link to a page on Apple's or Alba's site, opened in the browser. */
@Composable
internal fun HelpLink(
    label: Int,
    url: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = {
        try { uriHandler.openUri(url) }
        catch (_: ActivityNotFoundException) { Toast.makeText(context, R.string.browser_unavailable, Toast.LENGTH_LONG).show() }
    }, modifier = modifier, enabled = enabled, contentPadding = contentPadding) { Text(stringResource(label)) }
}

/**
 * A sign-out control that asks first, saying what leaves this phone and that iCloud is untouched. [content] draws the
 * control around the click that opens the confirmation.
 */
@Composable
internal fun ConfirmSignOutButton(
    title: Int = R.string.sign_out_confirm_title,
    action: Int = R.string.sign_out_confirm_action,
    onSignOut: () -> Unit,
    content: @Composable (onClick: () -> Unit) -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    content { confirming = true }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(R.string.sign_out_confirm_body)) },
        confirmButton = {
            TextButton(onClick = { confirming = false; onSignOut() }, modifier = Modifier.testTag("confirm-sign-out")) {
                Text(stringResource(action), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** iCloud refused the library because "Access iCloud Data on the Web" is off: what to change, and Apple's guide. */
@Composable
internal fun WebAccessOffCard() {
    AlbaCard(Modifier.testTag("web-access-off"), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.web_access_off_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.web_access_off_message), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer)
            HelpLink(R.string.web_access_off_help, WEB_ACCESS_URL)
        }
    }
}

/** Apple's account recovery, for forgotten passwords and locked accounts. */
internal const val FORGOT_PASSWORD_URL = "https://iforgot.apple.com"

/** Apple's "About Security Keys for Apple Account". */
private const val SECURITY_KEYS_URL = "https://support.apple.com/102637"

/** Apple's "Manage web access to your iCloud data". */
private const val WEB_ACCESS_URL = "https://support.apple.com/102630"

@Composable
private fun ProgressState(title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
