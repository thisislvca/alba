package me.lvca.alba.app.ui.designsystem

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import me.lvca.alba.app.ui.navigation.NavigationMotion
import me.lvca.alba.app.ui.viewer.reduceGalleryMotion

/**
 * Measures of Alba's floating surfaces: the M3 Expressive floating toolbar and the floating menu, matched to
 * Google Photos 7.94 (its overflow menu is about 227dp wide with 48dp rows and 24dp leading icons).
 */
internal object AlbaFloatingDefaults {
    val ToolbarHeight = 64.dp
    val ToolbarPadding = 8.dp
    val ToolbarItemSpacing = 4.dp
    val ToolbarShadow = 3.dp

    /** Floating surfaces keep this far from the gesture area, like Photos' navigation. */
    val BottomMargin = 16.dp

    val MenuMinWidth = 227.dp
    val MenuMaxWidth = 280.dp
    val MenuItemHeight = 48.dp
    val MenuItemPadding = 16.dp
    val MenuIconSize = 24.dp
    val MenuGroupPadding = 8.dp
    val MenuShadow = 3.dp

    /** The anchor's gap to the window edge the menu keeps. */
    val MenuWindowMargin = 8.dp

    /** Floating bars rise in on Material's fast spatial spring and fade on the effects spring; reduced motion snaps. */
    fun enter(reducedMotion: Boolean): EnterTransition = if (reducedMotion) EnterTransition.None else
        slideInVertically(NavigationMotion.fastSpatial()) { it } + fadeIn(NavigationMotion.fastEffects())

    fun exit(reducedMotion: Boolean): ExitTransition = if (reducedMotion) ExitTransition.None else
        slideOutVertically(NavigationMotion.fastSpatial()) { it } + fadeOut(NavigationMotion.fastEffects())
}

/**
 * The M3 Expressive floating toolbar: a stadium on surfaceContainer with a soft shadow, 64dp tall, holding icon
 * buttons. Content defaults to [contentColor]; callers lay out their own items in the row.
 */
@Composable
internal fun AlbaFloatingToolbar(
    modifier: Modifier = Modifier,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer, contentColor = contentColor,
        shadowElevation = AlbaFloatingDefaults.ToolbarShadow) {
        Row(Modifier.height(AlbaFloatingDefaults.ToolbarHeight).padding(horizontal = AlbaFloatingDefaults.ToolbarPadding),
            horizontalArrangement = Arrangement.spacedBy(AlbaFloatingDefaults.ToolbarItemSpacing),
            verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/**
 * Alba's one floating menu, used for every overflow. It opens from its anchor (the layout it sits in, as with
 * Material's DropdownMenu): below it, or above when there is no room, aligned to the anchor's end. It grows from the
 * anchor on a fast spatial spring, so reopening mid-close picks up where it is, and snaps when motion is reduced.
 * Group items with [AlbaMenuDivider].
 */
@Composable
internal fun AlbaMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded
    if (!visibility.currentState && !visibility.targetState) return
    val margin = with(LocalDensity.current) { AlbaFloatingDefaults.MenuWindowMargin.roundToPx() }
    var origin by remember { mutableStateOf(TransformOrigin.Center) }
    val position = remember(margin) { AnchoredMenuPosition(margin) { origin = it } }
    Popup(popupPositionProvider = position, onDismissRequest = onDismissRequest, properties = PopupProperties(focusable = true)) {
        val reduce = reduceGalleryMotion()
        val transition = rememberTransition(visibility, label = "alba-menu")
        val scale by transition.animateFloat({ if (reduce) snap() else NavigationMotion.fastSpatial() }, label = "menu-scale") {
            if (it) 1f else MENU_CLOSED_SCALE
        }
        val alpha by transition.animateFloat({ if (reduce) snap() else NavigationMotion.fastEffects() }, label = "menu-alpha") {
            if (it) 1f else 0f
        }
        Surface(Modifier.padding(AlbaFloatingDefaults.MenuShadow).graphicsLayer {
            scaleX = scale; scaleY = scale; this.alpha = alpha; transformOrigin = origin
        }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface, shadowElevation = AlbaFloatingDefaults.MenuShadow) {
            Column(modifier.width(IntrinsicSize.Max)
                .widthIn(min = AlbaFloatingDefaults.MenuMinWidth, max = AlbaFloatingDefaults.MenuMaxWidth)
                .verticalScroll(rememberScrollState()).padding(vertical = AlbaFloatingDefaults.MenuGroupPadding), content = content)
        }
    }
}

/**
 * One menu row: a 24dp [leadingIcon], the [text] in body large and an optional [trailingIcon], 48dp tall.
 * Disabled rows keep their place at Material's disabled opacity.
 */
@Composable
internal fun AlbaMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val disabledAlpha = .38f
    Row(modifier.fillMaxWidth().heightIn(min = AlbaFloatingDefaults.MenuItemHeight).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = AlbaFloatingDefaults.MenuItemPadding),
        horizontalArrangement = Arrangement.spacedBy(AlbaFloatingDefaults.MenuItemPadding), verticalAlignment = Alignment.CenterVertically) {
        val icons = if (enabled) colors.onSurfaceVariant else colors.onSurface.copy(alpha = disabledAlpha)
        if (leadingIcon != null) CompositionLocalProvider(LocalContentColor provides icons) {
            Box(Modifier.size(AlbaFloatingDefaults.MenuIconSize), contentAlignment = Alignment.Center) { leadingIcon() }
        }
        Text(text, Modifier.weight(1f), color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = disabledAlpha),
            style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (trailingIcon != null) CompositionLocalProvider(LocalContentColor provides icons) {
            Box(Modifier.size(AlbaFloatingDefaults.MenuIconSize), contentAlignment = Alignment.Center) { trailingIcon() }
        }
    }
}

/** Separates groups of related items, as Photos' menus do. */
@Composable
internal fun AlbaMenuDivider() {
    HorizontalDivider(Modifier.padding(vertical = AlbaFloatingDefaults.MenuGroupPadding), color = MaterialTheme.colorScheme.outlineVariant)
}

/** A closed menu is this much smaller, growing from its anchor. */
private const val MENU_CLOSED_SCALE = .8f

/** Below the anchor, else above it, aligned to its end and kept [margin] inside the window. */
private class AnchoredMenuPosition(private val margin: Int, private val onOrigin: (TransformOrigin) -> Unit) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val width = popupContentSize.width
        val height = popupContentSize.height
        val endAligned = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - width else anchorBounds.left
        val x = endAligned.coerceIn(margin, (windowSize.width - width - margin).coerceAtLeast(margin))
        val below = anchorBounds.bottom
        val above = anchorBounds.top - height
        val y = when {
            below + height <= windowSize.height - margin -> below
            above >= margin -> above
            else -> (windowSize.height - height - margin).coerceAtLeast(margin)
        }
        val pivotX = if (width > 0) ((anchorBounds.center.x - x).toFloat() / width).coerceIn(0f, 1f) else TransformOrigin.Center.pivotFractionX
        val pivotY = if (y >= anchorBounds.top) 0f else 1f
        onOrigin(TransformOrigin(pivotX, pivotY))
        return IntOffset(x, y)
    }
}
