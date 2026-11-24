package me.lvca.alba.app.ui.account

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.account.SignInProblem
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.AlbaPageScaffold
import me.lvca.alba.app.ui.designsystem.albaGroupColor
import me.lvca.alba.app.ui.designsystem.theme.AlbaIconSize
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing

/**
 * Signing in with an Apple Account. It leads with Alba's own mark joined to iCloud, so the page reads as Alba
 * connecting to iCloud rather than as Apple asking for a password, and keeps the action at the bottom, above the
 * keyboard. What Alba can't sign in to waits behind "Compatibility details" instead of greeting everyone.
 */
@Composable
internal fun SignInPage(
    expired: Boolean,
    appleId: String,
    password: String,
    problem: SignInProblem?,
    onAppleIdChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val canSubmit = appleId.isNotBlank() && password.isNotBlank()
    AlbaPageScaffold(title = "", onBack = onBack, bottomBar = { SignInFooter(canSubmit, onSubmit) }) {
        ConnectionMark(Modifier.padding(top = AlbaSpacing.sm))
        Spacer(Modifier.height(AlbaSpacing.xxl))
        Text(stringResource(R.string.sign_in_heading), style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() }.testTag("sign-in-title"))
        Spacer(Modifier.height(AlbaSpacing.sm))
        Text(stringResource(R.string.sign_in_intro), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (expired) {
            Spacer(Modifier.height(AlbaSpacing.md))
            Text(stringResource(R.string.your_saved_library_is_available_sign_in_again_to_reconnect),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("sign-in-expired"))
        }
        Spacer(Modifier.height(28.dp))
        SignInFields(appleId, password, problem, onAppleIdChange, onPasswordChange) { if (canSubmit) onSubmit() }
        SignInProblemNotice(problem)
        Spacer(Modifier.height(AlbaSpacing.lg))
        CompatibilityDetails()
    }
}

/** Alba's app icon and an iCloud tile joined by a dotted line: decoration, so screen readers skip it. */
@Composable
private fun ConnectionMark(modifier: Modifier = Modifier) {
    Row(modifier.testTag("sign-in-connection-mark").clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically) {
        // The same tile as the first onboarding page: the lightest surface, raised slightly.
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shadowElevation = 1.dp, modifier = Modifier.size(MarkTile)) {
            Image(painterResource(R.drawable.alba_logo_mark), null, Modifier.size(MarkTile))
        }
        Row(Modifier.padding(horizontal = AlbaSpacing.md), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(CONNECTOR_DOTS) {
                Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = .6f), CircleShape))
            }
        }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(MarkTile)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(AlbaIcons.CloudQueue, null, Modifier.size(AlbaIconSize.hero), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun SignInFields(
    appleId: String,
    password: String,
    problem: SignInProblem?,
    onAppleIdChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val wrongPassword = problem == SignInProblem.WRONG_PASSWORD
    // Filled with the card colour so the fields sit on the grey page as the onboarding cards do.
    val colors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = albaGroupColor(), unfocusedContainerColor = albaGroupColor(),
        errorContainerColor = albaGroupColor(),
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    )
    OutlinedTextField(
        value = appleId, onValueChange = onAppleIdChange,
        label = { Text(stringResource(R.string.apple_account)) },
        placeholder = { Text(stringResource(R.string.email_placeholder)) },
        leadingIcon = { Icon(Icons.Outlined.Person, null) },
        singleLine = true, shape = MaterialTheme.shapes.medium, colors = colors,
        isError = wrongPassword,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next,
            capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
        modifier = Modifier.fillMaxWidth().testTag("sign-in-apple-id")
            .semantics { contentType = ContentType.Username + ContentType.EmailAddress },
    )
    Spacer(Modifier.height(AlbaSpacing.md))
    OutlinedTextField(
        value = password, onValueChange = onPasswordChange,
        label = { Text(stringResource(R.string.password)) },
        leadingIcon = { Icon(AlbaIcons.Key, null) },
        singleLine = true, shape = MaterialTheme.shapes.medium, colors = colors,
        isError = wrongPassword,
        supportingText = if (wrongPassword) { { Text(stringResource(R.string.invalid_credentials)) } } else null,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }, modifier = Modifier.testTag("sign-in-show-password")) {
                Icon(if (showPassword) AlbaIcons.VisibilityOff else AlbaIcons.Visibility,
                    stringResource(if (showPassword) R.string.sign_in_hide_password else R.string.sign_in_show_password))
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done,
            autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().testTag("sign-in-password").semantics { contentType = ContentType.Password },
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        HelpLink(R.string.sign_in_forgot_password, FORGOT_PASSWORD_URL, Modifier.testTag("sign-in-forgot-password"))
    }
}

/** Which accounts Alba can and can't sign in to, for whoever asks. */
@Composable
private fun ColumnScope.CompatibilityDetails() {
    var shown by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { shown = !shown }, modifier = Modifier.align(Alignment.CenterHorizontally)
        .testTag("sign-in-compatibility")) {
        Text(stringResource(if (shown) R.string.sign_in_compatibility_hide else R.string.sign_in_compatibility),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(if (shown) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
            null, Modifier.padding(start = 6.dp).size(AlbaIconSize.inline), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (shown) {
        Column(Modifier.padding(horizontal = AlbaSpacing.md, vertical = AlbaSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AlbaSpacing.md)) {
            Text(stringResource(R.string.sign_in_unsupported_accounts), Modifier.testTag("sign-in-unsupported"),
                style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.supported_in_v1_normal_two_factor_authentication_and_the_personal_library_security_ke),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The one reassurance the page makes, then the action, pinned to the bottom of the page. */
@Composable
private fun SignInFooter(canSubmit: Boolean, onSubmit: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("sign-in-trust"), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Lock, null, Modifier.size(AlbaIconSize.badge), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.sign_in_trust), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(AlbaSpacing.md))
    Button(onClick = onSubmit, enabled = canSubmit,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("sign-in-submit")) {
        Text(stringResource(R.string.sign_in_submit))
    }
}

private val MarkTile = 64.dp
private const val CONNECTOR_DOTS = 3
