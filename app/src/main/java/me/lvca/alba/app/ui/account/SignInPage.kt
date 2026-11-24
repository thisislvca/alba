package me.lvca.alba.app.ui.account

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import me.lvca.alba.app.R
import me.lvca.alba.app.account.SignInProblem
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.LightStatusBarIcons
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.designsystem.theme.DawnColors
import me.lvca.alba.app.ui.viewer.reduceGalleryMotion
import kotlin.math.min

/**
 * Signing in with an Apple Account: Alba's sunrise, with the app icon and name centred in it, over a sheet holding the
 * form. The page says "this is Alba" before it asks for anything. The sheet takes the height it needs and the sunrise
 * the rest, so the keyboard pushes the sheet up and the brand shrinks to fit. Wide landscape windows put the sunrise
 * beside the sheet. There is no back button: at the start of the app nothing is behind sign-in, and elsewhere the
 * system's back gesture leaves it. [onTryDemo] offers the sample library where the build allows it.
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
    onTryDemo: (() -> Unit)? = null,
) {
    LightStatusBarIcons()
    val dawn = AlbaTheme.colors.dawn
    val canSubmit = appleId.isNotBlank() && password.isNotBlank()
    // Where the sunrise ends: the sheet's top edge, or the bottom of the window beside it.
    var horizon by remember { mutableFloatStateOf(Float.NaN) }
    // Beside the sunrise, the sheet runs the window's height and holds the form in its middle.
    val sheet: @Composable (Modifier, Boolean) -> Unit = { modifier, centred ->
        SignInSheet(modifier, centred, expired, appleId, password, problem, canSubmit, onAppleIdChange, onPasswordChange, onSubmit,
            onTryDemo)
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("sign-in-page").drawBehind { drawDawn(dawn, horizon) }) {
        val sideBySide = maxWidth > maxHeight && maxWidth >= 600.dp
        val horizontal = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        if (sideBySide) {
            Row(Modifier.fillMaxSize().onGloballyPositioned { horizon = Float.NaN }) {
                Brand(dawn, Modifier.weight(1f).fillMaxHeight().statusBarsPadding().navigationBarsPadding().then(horizontal))
                sheet(Modifier.width(440.dp).fillMaxHeight().statusBarsPadding().padding(top = AlbaSpacing.lg), true)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                // The brand is centred in the sky that shows: below the status bar, above the sheet.
                Brand(dawn, Modifier.weight(1f).statusBarsPadding().then(horizontal))
                sheet(Modifier.fillMaxWidth().onGloballyPositioned { horizon = it.positionInRoot().y }, false)
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

/**
 * Alba's icon, name and promise, centred in the space they are given. They give way as the sheet grows (when the
 * keyboard opens, at large text sizes, on small screens): first the promise fades out, then the icon and name scale
 * down and fade. Each step follows the space continuously, so the brand moves with the keyboard's own animation and
 * adds none of its own.
 */
@Composable
private fun Brand(dawn: DawnColors, modifier: Modifier = Modifier) {
    Layout(
        content = {
            Surface(shape = RoundedCornerShape(BrandTileCorner), color = Color.White, shadowElevation = 10.dp,
                modifier = Modifier.size(BrandTile)) {
                // The mark's artwork is centred in its square, so it sits optically centred in the tile.
                Image(painterResource(R.drawable.alba_logo_mark), null, Modifier.size(BrandTile))
            }
            Text(stringResource(R.string.brand_name), style = brandNameStyle(), color = dawn.onSky,
                modifier = Modifier.padding(top = AlbaSpacing.xl).semantics { heading() })
            Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp,
                lineHeight = 24.sp), color = dawn.onSky, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = AlbaSpacing.sm, start = AlbaSpacing.xxl, end = AlbaSpacing.xxl))
        },
        modifier = modifier.fillMaxWidth().testTag("sign-in-brand"),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val (tile, name, promise) = measurables.map { it.measure(loose) }
        val width = constraints.maxWidth
        val inset = BrandInset.roundToPx()
        val natural = tile.height + name.height + promise.height + 2 * inset
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else natural
        val room = (height - 2 * inset).coerceAtLeast(0).toFloat()
        val core = (tile.height + name.height).toFloat()
        // The promise fades over the last quarter of its own height, so at rest it is either there in full or gone.
        val promiseShown = (((room - core) / promise.height - PROMISE_FADE_START) / (1f - PROMISE_FADE_START)).coerceIn(0f, 1f)
        val scale = min(1f, room / core)
        val coreAlpha = ((scale - MIN_BRAND_SCALE) / BRAND_FADE).coerceIn(0f, 1f)
        layout(width, height) {
            var y = (height - core * scale - promise.height * promiseShown) / 2
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

/** The name as a wordmark: the display style, larger, bold and set tight. */
@Composable
private fun brandNameStyle() = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, fontSize = 46.sp,
    lineHeight = 50.sp, letterSpacing = (-1.2).sp)

@Composable
private fun SignInSheet(
    modifier: Modifier,
    centred: Boolean,
    expired: Boolean,
    appleId: String,
    password: String,
    problem: SignInProblem?,
    canSubmit: Boolean,
    onAppleIdChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onTryDemo: (() -> Unit)?,
) {
    Surface(modifier.testTag("sign-in-sheet"), shape = RoundedCornerShape(topStart = SheetCorner, topEnd = SheetCorner),
        color = MaterialTheme.colorScheme.surface) {
        // The sheet runs under the keyboard and the navigation bar, and keeps its content above both.
        Column(Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            verticalArrangement = if (centred) Arrangement.Center else Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()
                .padding(start = AlbaSpacing.xxl, end = AlbaSpacing.xxl, top = 28.dp, bottom = AlbaSpacing.xs)) {
                // Only the form scrolls, so the button stays in reach at large text sizes and in short windows.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("sign-in-form")) {
                    Text(stringResource(R.string.sign_in_heading), style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() }.testTag("sign-in-title"))
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(if (expired) R.string.your_saved_library_is_available_sign_in_again_to_reconnect
                        else R.string.sign_in_intro),
                        style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = if (expired) Modifier.testTag("sign-in-expired") else Modifier)
                    Spacer(Modifier.height(AlbaSpacing.xl))
                    SignInFields(appleId, password, problem, onAppleIdChange, onPasswordChange) { if (canSubmit) onSubmit() }
                    SignInProblemNotice(problem)
                }
                Spacer(Modifier.height(AlbaSpacing.md))
                Button(onClick = onSubmit, enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("sign-in-submit")) {
                    Text(stringResource(R.string.sign_in_submit), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(AlbaSpacing.lg))
                TrustLine()
                SheetLinks(onTryDemo)
            }
        }
    }
}

/**
 * Both fields in one rounded card, as Apple groups them: a soft tonal fill, a hairline between the two, and no outline
 * until a field has focus, when the card takes a fine accent edge (the error colour while the password is wrong).
 */
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
    val appleIdInteraction = remember { MutableInteractionSource() }
    val passwordInteraction = remember { MutableInteractionSource() }
    val focused = appleIdInteraction.collectIsFocusedAsState().value || passwordInteraction.collectIsFocusedAsState().value
    val colors = MaterialTheme.colorScheme
    val edge by animateColorAsState(
        when {
            wrongPassword -> colors.error
            focused -> colors.primary
            else -> Color.Transparent
        },
        if (reduceGalleryMotion()) snap() else tween(FOCUS_MILLIS), label = "sign-in-field-edge",
    )
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        errorContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        errorIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
        unfocusedLabelColor = colors.onSurfaceVariant, focusedLabelColor = colors.primary,
    )
    val shape = RoundedCornerShape(FieldCorner)
    Column(Modifier.fillMaxWidth().clip(shape).background(colors.surfaceContainerHigh)
        .border(BorderStroke(FieldEdge, edge), shape).testTag("sign-in-fields")) {
        TextField(
            value = appleId, onValueChange = onAppleIdChange,
            label = { Text(stringResource(R.string.apple_account)) },
            placeholder = { Text(stringResource(R.string.email_placeholder)) },
            singleLine = true, isError = wrongPassword, colors = fieldColors, interactionSource = appleIdInteraction,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next,
                capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.fillMaxWidth().testTag("sign-in-apple-id")
                .semantics { contentType = ContentType.Username + ContentType.EmailAddress },
        )
        HorizontalDivider(Modifier.padding(start = AlbaSpacing.lg), thickness = Dp.Hairline, color = colors.outlineVariant)
        TextField(
            value = password, onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.password)) },
            singleLine = true, isError = wrongPassword, colors = fieldColors, interactionSource = passwordInteraction,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }, modifier = Modifier.testTag("sign-in-show-password")) {
                    Icon(if (showPassword) AlbaIcons.VisibilityOff else AlbaIcons.Visibility,
                        stringResource(if (showPassword) R.string.sign_in_hide_password else R.string.sign_in_show_password),
                        tint = colors.onSurfaceVariant)
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done,
                autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().testTag("sign-in-password").semantics { contentType = ContentType.Password },
        )
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (wrongPassword) {
            Text(stringResource(R.string.invalid_credentials), style = MaterialTheme.typography.bodySmall, color = colors.error,
                modifier = Modifier.weight(1f).padding(start = AlbaSpacing.lg, end = AlbaSpacing.sm)
                    .semantics { liveRegion = LiveRegionMode.Polite }.testTag("sign-in-wrong-password"))
        } else {
            Spacer(Modifier.weight(1f))
        }
        HelpLink(R.string.sign_in_forgot_password, FORGOT_PASSWORD_URL, Modifier.testTag("sign-in-forgot-password"),
            contentPadding = LinkPadding)
    }
}

/** The one reassurance the page makes, under the action. The lock runs with the text, so it stays beside it when it wraps. */
@Composable
private fun TrustLine() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val lock = mapOf(LOCK to InlineTextContent(Placeholder(1.1.em, 1.1.em, PlaceholderVerticalAlign.TextCenter)) {
        Icon(Icons.Outlined.Lock, null, Modifier.fillMaxSize(), tint = color)
    })
    val trust = stringResource(R.string.sign_in_trust)
    // The lock is decoration: the line reads as its words alone.
    Text(buildAnnotatedString { appendInlineContent(LOCK); append("\u2002"); append(trust) },
        Modifier.fillMaxWidth().clearAndSetSemantics { testTag = "sign-in-trust"; text = AnnotatedString(trust) },
        inlineContent = lock,
        style = MaterialTheme.typography.bodySmall.copy(lineBreak = LineBreak.Heading), color = color,
        textAlign = TextAlign.Center)
}

/** Quiet links under the trust line: which accounts work, and the demo where the build offers it. */
@Composable
private fun SheetLinks(onTryDemo: (() -> Unit)?) {
    var compatibility by rememberSaveable { mutableStateOf(false) }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically) {
        QuietLink(R.string.sign_in_compatibility, Modifier.testTag("sign-in-compatibility")) { compatibility = true }
        if (onTryDemo != null) {
            Text("·", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
            QuietLink(R.string.welcome_demo, Modifier.testTag("sign-in-demo"), onTryDemo)
        }
    }
    if (compatibility) CompatibilityDialog { compatibility = false }
}

@Composable
private fun QuietLink(label: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier, contentPadding = LinkPadding) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Which accounts Alba can and can't sign in to, for whoever asks. The details scroll on their own in short windows. */
@Composable
private fun CompatibilityDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Info, null) },
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

private const val LOCK = "lock"
private val BrandTile = 80.dp
private val BrandTileCorner = 22.dp
private val BrandInset = 16.dp
private val SheetCorner = 28.dp
private val FieldCorner = 16.dp
private val FieldEdge = 1.5.dp
private val LinkPadding = PaddingValues(horizontal = AlbaSpacing.sm, vertical = AlbaSpacing.xs)
private const val FOCUS_MILLIS = 150

// Where each colour of the dawn sits between the top of the window and the horizon. The violet holds for the upper
// two thirds, behind the brand's white text; the warm colours gather just above the sheet.
private const val SKY_HIGH_STOP = .66f
private const val ROSE_STOP = .84f
private const val CORAL_STOP = .94f

/** The sun's glow: its radius as a share of the width, and its strength at the horizon. */
private const val SUN_RADIUS = .75f
private const val SUN_GLOW = .75f

/** Below this scale the icon and name are gone rather than shrunk into an unreadable stamp; they fade out above it. */
private const val MIN_BRAND_SCALE = .35f
private const val BRAND_FADE = .1f
private const val PROMISE_FADE_START = .75f
