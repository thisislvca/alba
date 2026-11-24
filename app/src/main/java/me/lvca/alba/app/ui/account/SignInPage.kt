package me.lvca.alba.app.ui.account

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.lvca.alba.app.R
import me.lvca.alba.app.account.SignInProblem
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.LightStatusBarIcons
import me.lvca.alba.app.ui.designsystem.theme.AlbaIconSize
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.designsystem.theme.DawnColors
import kotlin.math.min

/**
 * Signing in with an Apple Account: Alba's sunrise, with the app icon and name, over a sheet holding the form. The page
 * says "this is Alba" before it asks for anything. The sheet takes the height it needs and the sunrise the rest, so the
 * keyboard pushes the sheet up and the brand shrinks to fit. Wide landscape windows put the sunrise beside the sheet.
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
    LightStatusBarIcons()
    val dawn = AlbaTheme.colors.dawn
    val canSubmit = appleId.isNotBlank() && password.isNotBlank()
    // Where the sunrise ends: the sheet's top edge, or the bottom of the window beside it.
    var horizon by remember { mutableFloatStateOf(Float.NaN) }
    val sheet: @Composable (Modifier) -> Unit = { modifier ->
        SignInSheet(modifier, expired, appleId, password, problem, canSubmit, onAppleIdChange, onPasswordChange, onSubmit)
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("sign-in-page").drawBehind { drawDawn(dawn, horizon) }) {
        val sideBySide = maxWidth > maxHeight && maxWidth >= 600.dp
        val horizontal = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        if (sideBySide) {
            Row(Modifier.fillMaxSize().onGloballyPositioned { horizon = Float.NaN }) {
                Column(Modifier.weight(1f).fillMaxHeight().then(horizontal)) {
                    BackRow(dawn, onBack)
                    Brand(dawn, Modifier.weight(1f))
                }
                sheet(Modifier.width(440.dp).fillMaxHeight().statusBarsPadding().padding(top = AlbaSpacing.lg))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                BackRow(dawn, onBack, horizontal)
                Brand(dawn, Modifier.weight(1f).then(horizontal))
                sheet(Modifier.fillMaxWidth().onGloballyPositioned { horizon = it.positionInRoot().y })
            }
        }
    }
}

/** The sky, sweeping from violet to the warm horizon at [horizon], with the sun's glow rising behind it. */
private fun DrawScope.drawDawn(dawn: DawnColors, horizon: Float) {
    val end = if (horizon.isNaN()) size.height else horizon + SheetCorner.toPx()
    drawRect(Brush.verticalGradient(
        0f to dawn.sky, SKY_HIGH_STOP to dawn.skyHigh, ROSE_STOP to dawn.rose, CORAL_STOP to dawn.coral, 1f to dawn.horizon,
        startY = 0f, endY = end,
    ))
    val radius = size.width * SUN_RADIUS
    drawCircle(Brush.radialGradient(listOf(dawn.sun.copy(alpha = SUN_GLOW), dawn.sun.copy(alpha = 0f)),
        center = Offset(size.width / 2, end), radius = radius), radius = radius, center = Offset(size.width / 2, end))
}

@Composable
private fun BackRow(dawn: DawnColors, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = AlbaSpacing.xs),
        contentAlignment = Alignment.CenterStart) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("sign-in-back")) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back_action), tint = dawn.onSky)
        }
    }
}

/**
 * Alba's icon, name and promise. They stay in the upper [BRAND_BAND] of the sky, where white text keeps its contrast,
 * and give way as the sheet grows (when the keyboard opens, at large text sizes, on small screens): first the promise
 * fades out, then the icon and name scale down and fade. Each step follows the space continuously, so the brand moves
 * with the keyboard's own animation and adds none of its own.
 */
@Composable
private fun Brand(dawn: DawnColors, modifier: Modifier = Modifier) {
    Layout(
        content = {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = Color.White, shadowElevation = 6.dp,
                modifier = Modifier.size(BrandTile)) {
                Image(painterResource(R.drawable.alba_logo_mark), null, Modifier.size(BrandTile))
            }
            Text(stringResource(R.string.brand_name), style = brandNameStyle(), color = dawn.onSky,
                modifier = Modifier.padding(top = AlbaSpacing.lg).semantics { heading() })
            Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.bodyLarge,
                color = dawn.onSky, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = AlbaSpacing.xs, start = AlbaSpacing.xxl, end = AlbaSpacing.xxl))
        },
        modifier = modifier.fillMaxWidth().testTag("sign-in-brand"),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val (tile, name, promise) = measurables.map { it.measure(loose) }
        val width = constraints.maxWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else tile.height + name.height + promise.height
        val room = height * BRAND_BAND
        val core = (tile.height + name.height).toFloat()
        // The promise fades over the last quarter of its own height, so at rest it is either there in full or gone.
        val promiseShown = (((room - core) / promise.height - PROMISE_FADE_START) / (1f - PROMISE_FADE_START)).coerceIn(0f, 1f)
        val scale = min(1f, room / core)
        val coreAlpha = ((scale - MIN_BRAND_SCALE) / BRAND_FADE).coerceIn(0f, 1f)
        layout(width, height) {
            var y = ((room - core * scale - promise.height * promiseShown) / 2).coerceAtLeast(0f)
            listOf(tile to coreAlpha, name to coreAlpha).forEach { (placeable, shown) ->
                placeable.placeRelativeWithLayer((width - placeable.width) / 2, y.toInt()) {
                    scaleX = scale; scaleY = scale; alpha = shown
                    transformOrigin = TransformOrigin(.5f, 0f)
                }
                y += placeable.height * scale
            }
            promise.placeRelativeWithLayer((width - promise.width) / 2, y.toInt()) { alpha = promiseShown * coreAlpha }
        }
    }
}

/** The name as a wordmark: the display style, larger and bold. */
@Composable
private fun brandNameStyle() =
    MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 52.sp)

@Composable
private fun SignInSheet(
    modifier: Modifier,
    expired: Boolean,
    appleId: String,
    password: String,
    problem: SignInProblem?,
    canSubmit: Boolean,
    onAppleIdChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Surface(modifier.testTag("sign-in-sheet"), shape = RoundedCornerShape(topStart = SheetCorner, topEnd = SheetCorner),
        color = MaterialTheme.colorScheme.surface) {
        // The sheet runs under the keyboard and the navigation bar, and keeps its content above both.
        Column(Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()
                .padding(start = AlbaSpacing.xxl, end = AlbaSpacing.xxl, top = AlbaSpacing.xxl, bottom = AlbaSpacing.sm)) {
                // Only the form scrolls, so the button stays in reach at large text sizes and in short windows.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("sign-in-form")) {
                    Text(stringResource(R.string.sign_in_heading), style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() }.testTag("sign-in-title"))
                    if (expired) {
                        Spacer(Modifier.height(AlbaSpacing.sm))
                        Text(stringResource(R.string.your_saved_library_is_available_sign_in_again_to_reconnect),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("sign-in-expired"))
                    }
                    Spacer(Modifier.height(AlbaSpacing.xl))
                    SignInFields(appleId, password, problem, onAppleIdChange, onPasswordChange) { if (canSubmit) onSubmit() }
                    SignInProblemNotice(problem)
                }
                Spacer(Modifier.height(AlbaSpacing.sm))
                Button(onClick = onSubmit, enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("sign-in-submit")) {
                    Text(stringResource(R.string.sign_in_submit))
                }
                Spacer(Modifier.height(AlbaSpacing.md))
                TrustLine()
                CompatibilityDetails()
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
    OutlinedTextField(
        value = appleId, onValueChange = onAppleIdChange,
        label = { Text(stringResource(R.string.apple_account)) },
        placeholder = { Text(stringResource(R.string.email_placeholder)) },
        leadingIcon = { Icon(Icons.Outlined.Person, null) },
        singleLine = true, shape = MaterialTheme.shapes.medium,
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
        singleLine = true, shape = MaterialTheme.shapes.medium,
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

/** The one reassurance the page makes, under the action. */
@Composable
private fun TrustLine() {
    Row(Modifier.fillMaxWidth().testTag("sign-in-trust"), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Lock, null, Modifier.size(AlbaIconSize.badge), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.sign_in_trust), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Which accounts Alba can and can't sign in to, for whoever asks. The details scroll on their own in short windows. */
@Composable
private fun CompatibilityDetails() {
    var shown by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = { shown = !shown }, modifier = Modifier.testTag("sign-in-compatibility")) {
            Text(stringResource(if (shown) R.string.sign_in_compatibility_hide else R.string.sign_in_compatibility),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (shown) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                null, Modifier.padding(start = AlbaSpacing.xs).size(AlbaIconSize.inline),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (shown) {
            Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = AlbaSpacing.xs, vertical = AlbaSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(AlbaSpacing.sm)) {
                Text(stringResource(R.string.sign_in_unsupported_accounts), Modifier.testTag("sign-in-unsupported"),
                    style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.supported_in_v1_normal_two_factor_authentication_and_the_personal_library_security_ke),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val BrandTile = 76.dp
private val SheetCorner = 28.dp

// Where each colour of the dawn sits between the top of the window and the horizon. The violet holds for the upper
// half, behind the brand's white text; the warm colours gather just above the sheet.
private const val SKY_HIGH_STOP = .6f
private const val ROSE_STOP = .8f
private const val CORAL_STOP = .92f

/** The sun's glow: its radius as a share of the width, and its strength at the horizon. */
private const val SUN_RADIUS = .75f
private const val SUN_GLOW = .75f

/** The share of the sky the brand may use: the upper part, where white text keeps its contrast. */
private const val BRAND_BAND = .46f

/** Below this scale the icon and name are gone rather than shrunk into an unreadable stamp; they fade out above it. */
private const val MIN_BRAND_SCALE = .35f
private const val BRAND_FADE = .1f
private const val PROMISE_FADE_START = .75f
