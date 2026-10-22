package me.lvca.alba.app.ui.account

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.DiagnosticsConsent
import me.lvca.alba.app.R
import me.lvca.alba.app.account.PhoneStorageState
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.demo.DemoTransferMode
import me.lvca.alba.app.messages.localizedStoredMessage
import me.lvca.alba.app.transfers.TransferState
import me.lvca.alba.app.ui.AccountActions
import me.lvca.alba.app.ui.AccountScreenActions
import me.lvca.alba.app.ui.designsystem.AlbaCard
import me.lvca.alba.app.ui.designsystem.AlbaDivider
import me.lvca.alba.app.ui.designsystem.AlbaPageScaffold
import me.lvca.alba.app.ui.localized
import me.lvca.alba.app.ui.navigation.BackPhase
import me.lvca.alba.app.ui.navigation.NavigationDirection
import me.lvca.alba.app.ui.navigation.NavigationMotion
import me.lvca.alba.app.ui.navigation.PredictiveBackNavigation
import me.lvca.alba.app.ui.navigation.PredictiveBackState
import me.lvca.alba.app.ui.navigation.navigationTransform
import me.lvca.alba.app.ui.navigation.predictiveBackPose
import me.lvca.alba.app.ui.navigation.rememberNavigationTransition
import me.lvca.alba.app.ui.timestampLabel
import me.lvca.alba.app.ui.transfers.TransferQueueCard
import me.lvca.alba.app.ui.viewer.reduceGalleryMotion
import me.lvca.alba.engine.model.BackupView
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.TransferViewState
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
) {
    val state = session.accountState
    var page by rememberSaveable { mutableStateOf(ProfilePage.OVERVIEW) }
    var appleId by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }
    val signedIn = state as? ICloudAccountState.SignedIn
    LaunchedEffect(state) {
        if (signedIn?.status == SessionStatus.VERIFIED && page == ProfilePage.SIGN_IN) page = ProfilePage.OVERVIEW
    }
    LaunchedEffect(page) {
        if (page == ProfilePage.OVERVIEW) actions.checkPhoneStorage()
    }
    val back = {
        if (page == ProfilePage.OVERVIEW) actions.closeAccount() else {
            page = ProfilePage.OVERVIEW
            password = ""
            verificationCode = ""
        }
    }
    val reduceMotion = reduceGalleryMotion()
    val pageBack = remember { PredictiveBackState() }
    val (pageState, pageTransition) = rememberNavigationTransition(page, pageBack, reduceMotion, label = "account-pages")
    PredictiveBackNavigation(pageState, pageBack, previous = ProfilePage.OVERVIEW.takeIf { page != ProfilePage.OVERVIEW },
        key = { it }, reduceMotion = reduceMotion) { back() }
    val slide = with(LocalDensity.current) { NavigationMotion.SHARED_AXIS_DP.dp.roundToPx() }
    pageTransition.AnimatedContent(
        transitionSpec = {
            val direction = when {
                targetState == ProfilePage.OVERVIEW -> NavigationDirection.BACK
                initialState == ProfilePage.OVERVIEW -> NavigationDirection.FORWARD
                else -> NavigationDirection.LATERAL
            }
            navigationTransform(direction, slide, gesture = pageBack.phase != BackPhase.IDLE, reduceMotion = reduceMotion)
        },
        modifier = Modifier.fillMaxSize(),
    ) { shown ->
        Box(Modifier.fillMaxSize().predictiveBackPose(pageBack, shown)) {
            AccountPage(shown, session, transfers, phoneStorage, mediaBusy, diagnostics, actions,
                appleId = appleId, password = password, verificationCode = verificationCode,
                onAppleId = { appleId = it }, onPassword = { password = it }, onVerificationCode = { verificationCode = it },
                onNavigate = { page = it }, onBack = back)
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
) {
    val state = session.accountState
    val busy = session.accountBusy
    val signedIn = state as? ICloudAccountState.SignedIn
    val authenticating = state is ICloudAccountState.SigningIn || state is ICloudAccountState.AwaitingTwoFactor ||
        state == ICloudAccountState.Restoring
    if (page == ProfilePage.ABOUT) {
        AboutScreen(diagnostics, actions::setDiagnosticsEnabled, onBack)
        return
    }
    AlbaPageScaffold(
        title = stringResource(when {
            authenticating -> R.string.icloud_account
            state == ICloudAccountState.SignedOut -> R.string.icloud_account
            else -> page.title
        }),
        onBack = onBack,
    ) {
        when {
            state == ICloudAccountState.Restoring -> if (session.restoreFailed) {
                Text(stringResource(R.string.the_saved_account_could_not_be_opened_your_library_has_not_been_replaced))
                Button(onClick = actions::retryRestore) { Text(stringResource(R.string.retry_saved_account)) }
                OutlinedButton(onClick = actions::signOut) { Text(stringResource(R.string.remove_saved_account_and_use_demo)) }
            } else ProgressState(
                stringResource(R.string.restoring_your_session),
                stringResource(R.string.checking_the_encrypted_icloud_session_stored_on_this_phone),
            )
            state is ICloudAccountState.SigningIn -> ProgressState(
                stringResource(R.string.signing_in), stringResource(R.string.connecting_as, state.appleId),
            )
            state is ICloudAccountState.AwaitingTwoFactor -> VerificationForm(
                busy, state, verificationCode,
                { onVerificationCode(it.filter(Char::isDigit).take(6)) },
                { actions.submitTwoFactor(verificationCode); onVerificationCode("") },
                actions::resendTwoFactor, actions::signOut,
            )
            page == ProfilePage.SIGN_IN || state == ICloudAccountState.SignedOut -> {
                if (signedIn?.status == SessionStatus.EXPIRED) {
                    Text(stringResource(R.string.your_saved_library_is_available_sign_in_again_to_reconnect))
                    Spacer(Modifier.height(20.dp))
                }
                SignInForm(
                    appleId.ifBlank { signedIn?.appleId.orEmpty() }, password,
                    onAppleId, onPassword,
                    { actions.signIn(appleId.ifBlank { signedIn?.appleId.orEmpty() }, password); onPassword("") },
                )
            }
            page == ProfilePage.BACKUP -> BackupSettings(
                connected = signedIn?.status == SessionStatus.VERIFIED,
                demo = state == ICloudAccountState.Demo,
                appleId = signedIn?.appleId.orEmpty(), backup = transfers.backup,
                session = session, transferQueue = transfers.queue, actions = actions,
            )
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
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(30.dp))
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
    OutlinedButton(onClick = onSignOut, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.sign_out_and_use_demo_library))
    }
}

@Composable
private fun SignInForm(
    appleId: String,
    password: String,
    onAppleIdChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    var showCompatibility by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(12.dp))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = .09f)) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.sign_in_heading), style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.sign_in_intro), Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(28.dp))
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
    )
    AlbaCard {
        TextField(
            value = appleId, onValueChange = onAppleIdChange,
            label = { Text(stringResource(R.string.apple_account)) },
            placeholder = { Text(stringResource(R.string.email_placeholder)) },
            singleLine = true, shape = RectangleShape, colors = fieldColors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        AlbaDivider(Modifier.padding(horizontal = 16.dp))
        TextField(
            value = password, onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.password)) },
            singleLine = true, shape = RectangleShape, colors = fieldColors,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { if (appleId.isNotBlank() && password.isNotBlank()) onSubmit() },
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.sign_in_password_note), Modifier.padding(horizontal = 12.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center)
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onSubmit,
        enabled = appleId.isNotBlank() && password.isNotBlank(),
        shape = CircleShape,
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
    ) { Text(stringResource(R.string.sign_in_to_icloud)) }
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = { showCompatibility = !showCompatibility }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (showCompatibility) R.string.sign_in_compatibility_hide else R.string.sign_in_compatibility))
        Icon(if (showCompatibility) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
            null, Modifier.padding(start = 6.dp).size(18.dp))
    }
    if (showCompatibility) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.alba_connects_straight_to_apple_s_icloud_web_services_your_password_is_used_only_for_),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.supported_in_v1_normal_two_factor_authentication_and_the_personal_library_security_ke),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VerificationForm(
    busy: Boolean,
    state: ICloudAccountState.AwaitingTwoFactor,
    code: String,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onResend: () -> Unit,
    onCancel: () -> Unit,
) {
    Text(stringResource(R.string.enter_the_verification_code), style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    val hasHint = !state.destinationHint.isNullOrBlank()
    val deliveryMessage = when (state.delivery) {
        TwoFactorDelivery.TRUSTED_DEVICE -> if (hasHint) R.string.code_sent_device_hint else R.string.code_sent_device
        TwoFactorDelivery.SMS -> if (hasHint) R.string.code_sent_sms_hint else R.string.code_sent_sms
    }
    Text(
        if (hasHint) stringResource(deliveryMessage, state.destinationHint.orEmpty()) else stringResource(deliveryMessage),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = code,
        onValueChange = onCodeChange,
        label = { Text(stringResource(R.string.six_digit_code)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { if (!busy && code.length == 6) onSubmit() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(18.dp))
    Button(
        onClick = onSubmit,
        enabled = !busy && code.length == 6,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (busy) stringResource(R.string.contacting_apple) else stringResource(R.string.verify_and_continue))
    }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(enabled = !busy, onClick = onResend, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.send_another_code))
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(enabled = !busy, onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.use_a_different_apple_account))
    }
}

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

@Composable
private fun BackupSettings(
    connected: Boolean,
    demo: Boolean,
    appleId: String,
    backup: BackupView,
    session: SessionState,
    transferQueue: List<TransferView>,
    actions: AccountActions,
) {
    if (demo) {
        Text(stringResource(R.string.demo_backup_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.demo_backup_disclosure), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
    }
    ProfileStorage(session, demo = demo, hasAccount = connected,
        check = actions::checkConnection, labelRes = R.string.cloud_storage)
    Spacer(Modifier.height(22.dp))
    AlbaCard {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(stringResource(R.string.automatic_jpeg_backup), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                if (demo) {
                    stringResource(if (backup.enabled) R.string.demo_backup_running else R.string.demo_backup_off)
                } else if (backup.enabled) {
                    stringResource(R.string.backup_on_for, backup.destinationLabel ?: appleId)
                } else {
                    stringResource(R.string.off_you_can_still_upload_one_photo_at_a_time_from_the_system_photo_picker)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (Build.VERSION.SDK_INT < 30) Text(stringResource(R.string.automatic_backup_requires_android_11_or_later_use_the_photo_picker_for_manual_uploads))
            backup.message?.let { Text(localizedStoredMessage(it).localized()) }
            Spacer(Modifier.height(14.dp))
            if (backup.enabled) {
                OutlinedButton(
                    onClick = actions::disableAutomaticBackup,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.turn_off_automatic_backup)) }
            } else {
                Button(
                    enabled = (connected || demo) && Build.VERSION.SDK_INT >= 30,
                    onClick = actions::enableAutomaticBackup,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(if (demo) R.string.demo_backup_start else R.string.set_up_automatic_backup)) }
            }
        }
    }
    if (demo) {
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.demo_sync_description), style = MaterialTheme.typography.bodyMedium)
        session.lastRefreshedAt?.let {
            Text(stringResource(R.string.library_refreshed_at, timestampLabel(it)),
                style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = actions::checkConnection, enabled = !session.isRefreshing,
            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.demo_sync_refresh)) }
    }
    if (demo && backup.enabled) {
        var confirmCleanup by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { actions.demoBackupAction(TransferId(DemoTransferMode.CONTROL_INTERRUPT)) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.demo_backup_interrupt))
        }
        OutlinedButton(onClick = { actions.demoBackupAction(TransferId(DemoTransferMode.CONTROL_WIFI)) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.demo_backup_pause_wifi))
        }
        OutlinedButton(onClick = { confirmCleanup = true }, modifier = Modifier.fillMaxWidth(),
            enabled = transferQueue.isNotEmpty() && transferQueue.all { row -> row.state == TransferViewState.VERIFIED }) {
            Text(stringResource(R.string.demo_cleanup_preview))
        }
        if (confirmCleanup) AlertDialog(onDismissRequest = { confirmCleanup = false },
            title = { Text(stringResource(R.string.demo_cleanup_preview)) },
            text = { Text(stringResource(R.string.demo_cleanup_disclosure)) },
            confirmButton = { TextButton(onClick = { confirmCleanup = false; actions.demoBackupAction(TransferId(DemoTransferMode.CONTROL_CLEANUP)) }) { Text(stringResource(R.string.welcome_continue)) } },
            dismissButton = { TextButton(onClick = { confirmCleanup = false }) { Text(stringResource(R.string.not_now)) } })
    }
    if (demo && transferQueue.isNotEmpty()) {
        Spacer(Modifier.height(18.dp))
        TransferQueueCard(transferQueue, actions::demoBackupAction, demo = true)
    }
}
