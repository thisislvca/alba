package me.lvca.alba.app.ui.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.adaptive.albaLayout
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing
import me.lvca.alba.app.ui.designsystem.theme.sheet

/**
 * A utility page: a large, start-aligned title over a scrolling column, as Android's settings pages have. The title
 * collapses into the bar as the page scrolls, and the bar takes the surface-container tint once content passes under it.
 * A short window (a phone in landscape) keeps the small bar throughout, with the title inline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbaPageScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val background = MaterialTheme.colorScheme.background
    val largeTitle = albaLayout().largeTitles
    key(title) {
        val scroll = rememberScrollState()
        val barState = rememberTopAppBarState()
        val scrollBehavior = if (largeTitle) TopAppBarDefaults.exitUntilCollapsedScrollBehavior(barState)
            else TopAppBarDefaults.pinnedScrollBehavior(barState)
        Surface(color = background, modifier = Modifier.fillMaxSize()) {
            // Inset controls, not the scrolling viewport: cards can pass behind the gesture bar.
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).imePadding()) {
                // Bold, as the top-level tabs set their large titles, so every page reads as one family.
                val barTitle: @Composable () -> Unit = { Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                val back: @Composable () -> Unit = {
                    IconButton(onClick = onBack) { Icon(AlbaIcons.ArrowBack, stringResource(R.string.back_action)) }
                }
                val colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                )
                if (largeTitle) LargeTopAppBar(title = barTitle, navigationIcon = back, colors = colors, windowInsets = WindowInsets.statusBars,
                    scrollBehavior = scrollBehavior, modifier = Modifier.testTag("title-bar-large"))
                else TopAppBar(title = barTitle, navigationIcon = back, colors = colors, windowInsets = WindowInsets.statusBars,
                    scrollBehavior = scrollBehavior, modifier = Modifier.testTag("title-bar-small"))
                Column(Modifier.weight(1f).fillMaxWidth().nestedScroll(scrollBehavior.nestedScrollConnection)
                    .verticalScroll(scroll).testTag("alba-page-scroll"), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = AlbaSpacing.lg)
                        .padding(top = AlbaSpacing.md, bottom = AlbaSpacing.xxl).navigationBarsPadding(), content = content)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbaBottomSheet(
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
        shape = MaterialTheme.shapes.sheet,
        // Scrolling bodies supply their own trailing navigation inset. Avoid clipping the
        // whole sheet above the gesture area, which creates an opaque rectangular shelf.
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal) },
        // Limit the body, not the modal itself: its drag anchors need the full window
        // constraints to keep the sheet attached to the bottom edge. Reserve the handle.
        content = { Column(Modifier.heightIn(max = (maximumHeight - 48.dp).coerceAtLeast(0.dp)), content = content) },
    )
}

@Composable
internal fun AlbaSheetHeading(title: String, subtitle: String? = null, dismiss: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(bottom = AlbaSpacing.sm), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(top = AlbaSpacing.sm), verticalArrangement = Arrangement.spacedBy(AlbaSpacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        IconButton(onClick = dismiss, enabled = enabled) {
            Icon(AlbaIcons.Close, stringResource(R.string.close_surface), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
