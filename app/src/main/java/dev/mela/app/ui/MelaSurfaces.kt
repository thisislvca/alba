package dev.mela.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mela.app.R

/** Shared surfaces keep utility screens quiet while the gallery remains image-led. */
@Composable
internal fun melaGroupColor() = MaterialTheme.colorScheme.surfaceContainerLowest

@Composable
internal fun MelaGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = melaGroupColor()) {
        Column(content = content)
    }
}

@Composable
internal fun MelaPageScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val background = MaterialTheme.colorScheme.background
    Surface(color = background, modifier = Modifier.fillMaxSize()) {
        // Inset controls, not the scrolling viewport: cards can pass behind the gesture bar.
        Column(Modifier.statusBarsPadding().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).imePadding()) {
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back_action))
                }
                Text(title, Modifier.align(Alignment.Center).padding(horizontal = 56.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            key(title) {
                val scroll = rememberScrollState()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag("mela-page-scroll"), horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 20.dp)
                            .padding(top = 12.dp, bottom = 24.dp).navigationBarsPadding(), content = content)
                    }
                    if (scroll.canScrollBackward) Spacer(Modifier.fillMaxWidth().height(12.dp).background(
                        Brush.verticalGradient(listOf(background, background.copy(alpha = 0f)))))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MelaBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    maxHeightFraction: Float = .9f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val safeTop = with(density) { WindowInsets.safeDrawing.getTop(this).toDp() }
    val maximumHeight = minOf(windowHeight * maxHeightFraction, windowHeight - safeTop - 16.dp).coerceAtLeast(0.dp)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest, modifier = modifier, sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp,
        properties = ModalBottomSheetProperties(
            isAppearanceLightStatusBars = MaterialTheme.colorScheme.background.luminance() > .5f,
            isAppearanceLightNavigationBars = MaterialTheme.colorScheme.background.luminance() > .5f,
        ),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        // Scrolling bodies supply their own trailing navigation inset. Avoid clipping the
        // whole sheet above the gesture area, which creates an opaque rectangular shelf.
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal) },
        // Limit the body, not the modal itself: its drag anchors need the full window
        // constraints to keep the sheet attached to the bottom edge. Reserve the handle.
        content = { Column(Modifier.heightIn(max = (maximumHeight - 48.dp).coerceAtLeast(0.dp)), content = content) },
    )
}

@Composable
internal fun MelaSheetHeading(title: String, subtitle: String? = null, dismiss: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        IconButton(onClick = dismiss, enabled = enabled) {
            Icon(Icons.Outlined.Close, stringResource(R.string.close_surface), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
