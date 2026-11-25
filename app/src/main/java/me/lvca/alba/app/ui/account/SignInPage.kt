package me.lvca.alba.app.ui.account

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.account.SignInProblem
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.designsystem.theme.DawnColors
import me.lvca.alba.app.ui.designsystem.theme.pageHeadline
import me.lvca.alba.app.ui.navigation.NavigationMotion
import me.lvca.alba.app.ui.viewer.reduceGalleryMotion
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.TwoFactorDelivery

/** What the sign-in flow is showing, and what the user can do there. */
internal class SignInFlowState(
    /** Apple's code challenge, while it waits for the code; null on the Apple Account and password step. */
    val challenge: ICloudAccountState.AwaitingTwoFactor?,
    /** Apple is checking the password or code, or sending a new code: the step is read-only until it answers. */
    val busy: Boolean,
    /** Signing in again to a saved account whose session expired. */
    val expired: Boolean,
    val appleId: String,
    val password: String,
    val code: String,
    val problem: SignInProblem?,
)

/** What the sign-in flow asks of the account. */
internal class SignInFlowActions(
    val onAppleIdChange: (String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onCodeChange: (String) -> Unit,
    val onSignIn: () -> Unit,
    val onSubmitCode: (String) -> Unit,
    val onResendCode: () -> Unit,
    val onSendCodeBySms: () -> Unit,
    /** Leaves the code step for the Apple Account and password. */
    val onCancelCode: () -> Unit,
    /** The sample library, where the build offers it. */
    val onTryDemo: (() -> Unit)?,
)

/**
 * Signing in to iCloud, every step on one page: the Apple Account and password, then Apple's six-digit code when it
 * asks for one. Laid out as Android's own setup and account flows are (the mark top-start, a large start-aligned
 * headline, Material text fields, the next step bottom-end and the way out bottom-start) with Alba's dawn kept as a
 * glow across the top. While Apple answers, a progress bar runs along the top, the headline says what is happening
 * and nothing can be edited. In wide landscape windows the headline sits beside the form, as Pixel's setup does on
 * tablets.
 *
 * The first step has no back affordance: at the start of the app nothing is behind it. The code step's Back, and the
 * system back gesture there, return to the first step.
 */
@Composable
internal fun SignInFlow(state: SignInFlowState, actions: SignInFlowActions) {
    val dawn = AlbaTheme.colors.dawn
    val surface = MaterialTheme.colorScheme.surface
    val glowHeight = with(LocalDensity.current) { (WindowInsets.statusBars.getTop(this) + GlowHeight.roundToPx()).toFloat() }
    val reduceMotion = reduceGalleryMotion()
    val slide = with(LocalDensity.current) { NavigationMotion.SHARED_AXIS_DP.dp.roundToPx() }
    Box(Modifier.fillMaxSize().background(surface).drawBehind { drawDawnGlow(dawn, surface, glowHeight) }
        .testTag("sign-in-page")) {
        AnimatedContent(
            targetState = state.challenge,
            contentKey = { it != null },
            transitionSpec = { stepTransform(forward = targetState != null, slide, reduceMotion) },
            label = "sign-in-step",
        ) { challenge ->
            if (challenge == null) CredentialsStep(state, actions) else CodeStep(challenge, state, actions)
        }
    }
}

/** Shared axis between the steps on critically damped springs, so nothing overshoots; a cross-fade with reduced motion. */
private fun stepTransform(forward: Boolean, slidePx: Int, reduceMotion: Boolean): ContentTransform {
    val fade = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
    if (reduceMotion) return fadeIn(fade) togetherWith fadeOut(fade)
    val move = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = IntOffset(1, 1))
    val sign = if (forward) 1 else -1
    return (slideInHorizontally(move) { sign * slidePx } + fadeIn(fade))
        .togetherWith(slideOutHorizontally(move) { -sign * slidePx } + fadeOut(fade))
}

/**
 * Alba's dawn as a soft glow along the top of the page: the icon's violet, rose and gold sweeping across, fading into
 * the surface well above the form. It runs behind the status bar and stays quiet enough for its dark icons in light
 * mode; the dusk set, already deep, shows a little more of itself at night.
 */
private fun DrawScope.drawDawnGlow(dawn: DawnColors, surface: Color, height: Float) {
    val h = minOf(height, size.height)
    val alpha = if (surface.luminance() > LIGHT_SURFACE) GLOW_LIGHT else GLOW_DARK
    drawRect(Brush.linearGradient(listOf(dawn.sky, dawn.rose, dawn.coral, dawn.horizon)), size = Size(size.width, h),
        alpha = alpha)
    // The surface takes over on an ease-out curve, levelling off at the bottom, so the glow ends without an edge.
    val fade = List(FADE_STEPS) { step ->
        val t = step / (FADE_STEPS - 1f)
        surface.copy(alpha = 1f - (1f - t) * (1f - t))
    }
    drawRect(Brush.verticalGradient(fade, startY = 0f, endY = h), size = Size(size.width, h))
}

@Composable
private fun CredentialsStep(state: SignInFlowState, actions: SignInFlowActions) {
    val signingIn = state.busy
    val canSubmit = state.appleId.isNotBlank() && state.password.isNotBlank() && !signingIn
    val focus = LocalFocusManager.current
    val submit = {
        if (canSubmit) {
            focus.clearFocus()
            actions.onSignIn()
        }
    }
    var compatibility by rememberSaveable { mutableStateOf(false) }
    SignInLayout(
        progress = signingIn,
        headline = stringResource(if (signingIn) R.string.signing_in else R.string.sign_in_heading),
        body = when {
            signingIn -> stringResource(R.string.connecting_as, state.appleId.trim())
            state.expired -> stringResource(R.string.your_saved_library_is_available_sign_in_again_to_reconnect)
            else -> stringResource(R.string.sign_in_intro)
        },
        bodyTag = "sign-in-expired".takeIf { state.expired && !signingIn },
        secondary = actions.onTryDemo?.let { tryDemo ->
            {
                TextButton(onClick = tryDemo, enabled = !signingIn, modifier = Modifier.testTag("sign-in-demo")) {
                    Text(stringResource(R.string.welcome_demo))
                }
            }
        },
        primary = {
            Button(onClick = submit, enabled = canSubmit, modifier = Modifier.testTag("sign-in-submit")) {
                Text(stringResource(R.string.sign_in_submit))
            }
        },
    ) {
        CredentialFields(state, actions, onDone = submit)
        if (!signingIn) SignInProblemNotice(state.problem)
        Spacer(Modifier.height(AlbaSpacing.xxl))
        Text(stringResource(R.string.sign_in_trust), Modifier.testTag("sign-in-trust"),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { compatibility = true }, enabled = !signingIn,
            modifier = Modifier.offset(x = -TextButtonInset).testTag("sign-in-compatibility")) {
            Text(stringResource(R.string.sign_in_compatibility))
        }
    }
    if (compatibility) CompatibilityDialog { compatibility = false }
}

/**
 * The Apple Account and password as two Material outlined fields. A wrong password marks only the password field and
 * says so in its supporting text, which TalkBack reads with the field.
 */
@Composable
private fun CredentialFields(state: SignInFlowState, actions: SignInFlowActions, onDone: () -> Unit) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val wrongPassword = state.problem == SignInProblem.WRONG_PASSWORD && !state.busy
    OutlinedTextField(
        value = state.appleId, onValueChange = actions.onAppleIdChange,
        label = { Text(stringResource(R.string.apple_account)) },
        placeholder = { Text(stringResource(R.string.email_placeholder)) },
        singleLine = true, enabled = !state.busy, colors = fieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next,
            capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
        modifier = Modifier.fillMaxWidth().testTag("sign-in-apple-id")
            .semantics { contentType = ContentType.Username + ContentType.EmailAddress },
    )
    Spacer(Modifier.height(AlbaSpacing.lg))
    OutlinedTextField(
        value = state.password, onValueChange = actions.onPasswordChange,
        label = { Text(stringResource(R.string.password)) },
        singleLine = true, enabled = !state.busy, isError = wrongPassword, colors = fieldColors(),
        supportingText = if (wrongPassword) {
            { Text(stringResource(R.string.invalid_credentials), Modifier.testTag("sign-in-wrong-password")) }
        } else null,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }, enabled = !state.busy,
                modifier = Modifier.testTag("sign-in-show-password")) {
                Icon(if (showPassword) AlbaIcons.VisibilityOff else AlbaIcons.Visibility,
                    stringResource(if (showPassword) R.string.sign_in_hide_password else R.string.sign_in_show_password))
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done,
            autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().testTag("sign-in-password").semantics { contentType = ContentType.Password },
    )
    HelpLink(R.string.sign_in_forgot_password, FORGOT_PASSWORD_URL,
        Modifier.offset(x = -TextButtonInset).testTag("sign-in-forgot-password"), enabled = !state.busy)
}

/**
 * Apple's six-digit code. The field takes the code from the keyboard's suggestion or autofill and sends it as soon as
 * all six digits are in. While Apple checks it (or sends a new one) the field is read-only, its code still legible.
 */
@Composable
private fun CodeStep(challenge: ICloudAccountState.AwaitingTwoFactor, state: SignInFlowState, actions: SignInFlowActions) {
    val busy = state.busy
    // Which wait the progress is for: a new code being sent, or the code being checked.
    var resending by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val codeField = remember { FocusRequester() }
    LaunchedEffect(busy) {
        if (busy) focus.clearFocus() else {
            resending = false
            runCatching { codeField.requestFocus() }
        }
    }
    // Back returns to the first step; while Apple answers there is nothing to go back from.
    BackHandler { if (!busy) actions.onCancelCode() }
    val code = state.code
    val submit = { entered: String -> if (!busy && entered.length == CODE_LENGTH) actions.onSubmitCode(entered) }
    val hasHint = !challenge.destinationHint.isNullOrBlank()
    val delivery = when (challenge.delivery) {
        TwoFactorDelivery.TRUSTED_DEVICE -> if (hasHint) R.string.code_sent_device_hint else R.string.code_sent_device
        TwoFactorDelivery.SMS -> if (hasHint) R.string.code_sent_sms_hint else R.string.code_sent_sms
    }
    val wrongCode = state.problem == SignInProblem.WRONG_CODE && !busy
    SignInLayout(
        progress = busy,
        headline = stringResource(when {
            !busy -> R.string.enter_the_verification_code
            resending -> R.string.sign_in_sending_code
            else -> R.string.sign_in_checking_code
        }),
        body = if (busy) stringResource(R.string.contacting_apple)
            else if (hasHint) stringResource(delivery, challenge.destinationHint.orEmpty()) else stringResource(delivery),
        secondary = {
            TextButton(onClick = actions.onCancelCode, enabled = !busy, modifier = Modifier.testTag("sign-in-back")) {
                Text(stringResource(R.string.back_action))
            }
        },
        primary = {
            Button(onClick = { submit(code) }, enabled = !busy && code.length == CODE_LENGTH,
                modifier = Modifier.testTag("verification-submit")) {
                Text(stringResource(R.string.verify_and_continue))
            }
        },
    ) {
        OutlinedTextField(
            value = code,
            onValueChange = { typed ->
                val digits = typed.filter(Char::isDigit).take(CODE_LENGTH)
                actions.onCodeChange(digits)
                // The sixth digit sends the code, as Android's own verification screens do.
                if (digits.length == CODE_LENGTH && digits != code) submit(digits)
            },
            label = { Text(stringResource(R.string.six_digit_code)) },
            singleLine = true, readOnly = busy, isError = wrongCode, colors = fieldColors(),
            supportingText = if (wrongCode) {
                { Text(stringResource(R.string.invalid_two_factor_code), Modifier.testTag("verification-code-error")) }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit(code) }),
            modifier = Modifier.fillMaxWidth().focusRequester(codeField).testTag("verification-code")
                .semantics { contentType = ContentType.SmsOtpCode },
        )
        SignInProblemNotice(state.problem.takeIf { !busy })
        Spacer(Modifier.height(AlbaSpacing.sm))
        // Other ways to get a code, as quiet text actions under the field.
        FlowRow(Modifier.offset(x = -TextButtonInset)) {
            TextButton(onClick = { resending = true; actions.onResendCode() }, enabled = !busy,
                modifier = Modifier.testTag("code-resend")) {
                Text(stringResource(R.string.send_another_code))
            }
            if (challenge.smsAvailable) {
                TextButton(onClick = { resending = true; actions.onSendCodeBySms() }, enabled = !busy,
                    modifier = Modifier.testTag("code-by-sms")) {
                    Text(stringResource(R.string.sign_in_code_by_sms))
                }
            }
        }
    }
}

/**
 * Outlined fields filled with the surface, so the dawn never tints them where it reaches the form (wide windows put the
 * fields beside the headline, under the glow).
 */
@Composable
private fun fieldColors(): TextFieldColors {
    val surface = MaterialTheme.colorScheme.surface
    return OutlinedTextFieldDefaults.colors(focusedContainerColor = surface, unfocusedContainerColor = surface,
        disabledContainerColor = surface, errorContainerColor = surface)
}

/**
 * One step of signing in. On phones and narrow windows: the header (mark, headline, body) over the form, scrolling
 * together, with the buttons pinned above the keyboard. In wide landscape windows: the header on the left and the form
 * with its buttons on the right. The progress bar along the top shows while Apple answers.
 */
@Composable
private fun SignInLayout(
    progress: Boolean,
    headline: String,
    body: String,
    /** A test tag for the body, where a test checks which explanation it gives. */
    bodyTag: String? = null,
    secondary: (@Composable () -> Unit)?,
    primary: @Composable () -> Unit,
    form: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPane = maxWidth >= TwoPaneMinWidth && maxWidth > maxHeight
        Column(Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))) {
            ProgressStrip(progress)
            if (twoPane) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).navigationBarsPadding()
                        .padding(start = PageMargin, end = PaneGap / 2, top = PageTop, bottom = PageTop)) {
                        Header(headline, body, bodyTag)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("sign-in-form")
                            .padding(start = PaneGap / 2, end = PageMargin, top = PageTop + MarkHeight + AlbaSpacing.xl)) {
                            form()
                        }
                        ButtonBar(secondary, primary, Modifier.padding(start = PaneGap / 2 - BarInset, end = PageMargin - BarInset))
                    }
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.widthIn(max = SingleColumnMaxWidth).fillMaxWidth().testTag("sign-in-form")
                        .padding(start = PageMargin, end = PageMargin, top = PageTop, bottom = AlbaSpacing.lg)) {
                        Header(headline, body, bodyTag)
                        Spacer(Modifier.height(HeaderToForm))
                        form()
                    }
                }
                ButtonBar(secondary, primary, Modifier.align(Alignment.CenterHorizontally).widthIn(max = SingleColumnMaxWidth)
                    .padding(horizontal = PageMargin - BarInset))
            }
        }
    }
}

/** A thin indeterminate bar along the top while Apple answers; the space stays, so nothing below it moves. */
@Composable
private fun ProgressStrip(shown: Boolean) {
    Box(Modifier.fillMaxWidth().height(ProgressHeight)) {
        if (shown) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("sign-in-progress"))
    }
}

/** Alba's mark, the step's headline and what it is about. The text is a polite live region, so a new state is read out. */
@Composable
private fun Header(headline: String, body: String, bodyTag: String?) {
    Mark()
    Spacer(Modifier.height(AlbaSpacing.xl))
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(headline, style = MaterialTheme.typography.pageHeadline, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }.testTag("sign-in-title"))
        Spacer(Modifier.height(AlbaSpacing.md))
        Text(body, if (bodyTag != null) Modifier.testTag(bodyTag) else Modifier, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The icon's sunrise band on its own, cropped to the band so its left edge lines up with the headline. */
@Composable
private fun Mark() {
    val name = stringResource(R.string.brand_name)
    Box(Modifier.size(MarkWidth, MarkHeight).clipToBounds().semantics { contentDescription = name }.testTag("sign-in-brand"),
        contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.alba_logo_mark), null, Modifier.requiredSize(MarkWidth * MARK_ART_SCALE))
    }
}

/** The way out at the start, the way on at the end, above the keyboard and the navigation bar. */
@Composable
private fun ButtonBar(secondary: (@Composable () -> Unit)?, primary: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()
        .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))
        .padding(vertical = AlbaSpacing.lg).testTag("sign-in-buttons"),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { secondary?.invoke() }
        Spacer(Modifier.size(AlbaSpacing.sm))
        Box(Modifier.padding(end = BarInset)) { primary() }
    }
}

/** Which accounts Alba can and can't sign in to, for whoever asks. The details scroll on their own in short windows. */
@Composable
private fun CompatibilityDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Info, null) },
        title = { Text(stringResource(R.string.sign_in_compatibility_title), textAlign = TextAlign.Center) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AlbaSpacing.md)) {
                Text(stringResource(R.string.sign_in_unsupported_accounts), Modifier.testTag("sign-in-unsupported"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.supported_in_v1_normal_two_factor_authentication_and_the_personal_library_security_ke),
                    style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("sign-in-compatibility-close")) {
                Text(stringResource(R.string.close_surface))
            }
        },
        modifier = Modifier.testTag("sign-in-compatibility-dialog"),
    )
}

private const val CODE_LENGTH = 6

/** The page's side margin, and the gap between the two panes of the wide layout. */
private val PageMargin = 24.dp
private val PaneGap = 48.dp
private val PageTop = 24.dp
private val HeaderToForm = 32.dp

/**
 * The bar's buttons carry their own horizontal padding: the bar sits this much further out, so the button labels line
 * up with the page's margins rather than their containers.
 */
private val BarInset = 12.dp

/** A text button's own start padding: text actions in the form shift out by it, so their labels line up with the text. */
private val TextButtonInset = 12.dp
private val ProgressHeight = 4.dp
private val SingleColumnMaxWidth = 560.dp
private val TwoPaneMinWidth = 600.dp

/** The mark's band, and the scale of the square artwork it is cropped from (the band spans 700 of its 1024 px). */
private val MarkWidth = 48.dp
private val MarkHeight = 29.dp
private const val MARK_ART_SCALE = 1024f / 700f

/** How far the dawn reaches below the status bar, and how strongly it shows in each theme. */
private val GlowHeight = 196.dp
private const val GLOW_LIGHT = .26f
private const val GLOW_DARK = .55f
private const val FADE_STEPS = 5
private const val LIGHT_SURFACE = .5f
