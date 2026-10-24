package me.lvca.alba.app.ui.gallery

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.ui.collections.CollectionsPage
import me.lvca.alba.app.ui.navigation.BackPhase
import me.lvca.alba.app.ui.navigation.NavigationDirection
import me.lvca.alba.app.ui.navigation.NavigationMotion
import me.lvca.alba.app.ui.navigation.PredictiveBackNavigation
import me.lvca.alba.app.ui.navigation.PredictiveBackState
import me.lvca.alba.app.ui.navigation.navigationTransform
import me.lvca.alba.app.ui.navigation.predictiveBackPose
import me.lvca.alba.app.ui.navigation.rememberNavigationTransition
import me.lvca.alba.app.ui.viewer.LocalPhotoTransition
import me.lvca.alba.app.ui.viewer.reduceGalleryMotion
import me.lvca.alba.engine.model.GalleryQuery

/**
 * Which screen the gallery shows below its header: a destination and, in Collections, how deep into it. Only the
 * innermost folder is kept; [parent] and [depth] walk folders through the caller's album tree.
 */
internal data class GalleryScreen(
    val tab: GalleryTab,
    val page: CollectionsPage? = null,
    val folder: String? = null,
    val album: String? = null,
) {
    fun depth(folderDepth: (String) -> Int): Int =
        (if (page != null) 1 else 0) + (folder?.let(folderDepth) ?: 0) + (if (album != null) 1 else 0)

    /** Where back leads: out of the album, up a folder, out of a page, then to Photos. */
    fun parent(parentOf: (String) -> String?): GalleryScreen? = when {
        album != null -> copy(album = null)
        folder != null -> copy(folder = parentOf(folder))
        page != null -> copy(page = null)
        tab != GalleryTab.LIBRARY -> GalleryScreen(GalleryTab.LIBRARY)
        else -> null
    }

    /** True when back from this screen eventually reaches [other], or this is [other]. */
    fun isWithin(other: GalleryScreen, parentOf: (String) -> String?): Boolean =
        generateSequence(this) { it.parent(parentOf) }.take(MAX_DEPTH).any { it == other }

    val saveKey: String get() = "$tab|${page ?: ""}|${folder ?: ""}|${album ?: ""}"

    companion object {
        private const val MAX_DEPTH = 32

        /** The place a query browses, or null when the query is the library or a search rather than a place. */
        fun albumOf(query: GalleryQuery): String? = when {
            query.collectionId != null -> query.collectionId
            query.offlineOnly -> "offline"
            query.trashOnly -> "trash:${query.origin?.name.orEmpty()}"
            else -> null
        }
    }
}

/** A search with nothing typed or chosen yet, which shows the search suggestions instead of results. */
internal val GalleryQuery.isBlankSearch: Boolean
    get() {
        val noDates = fromDate == null && throughDate == null
        return filename.isBlank() && noDates && collectionId == null
    }

/** Shared axis into and out of Collections, fade through between destinations. */
internal fun galleryDirection(from: GalleryScreen, to: GalleryScreen, folderDepth: (String) -> Int): NavigationDirection {
    if (from.tab != to.tab) return NavigationDirection.LATERAL
    val before = from.depth(folderDepth)
    val after = to.depth(folderDepth)
    return when {
        after > before -> NavigationDirection.FORWARD
        after < before -> NavigationDirection.BACK
        else -> NavigationDirection.LATERAL
    }
}

/** Deeper than any real album tree; stops a malformed parent cycle. */
private const val MAX_FOLDER_DEPTH = 32

/** How many folders deep [folder] is, counting itself. */
internal fun folderDepth(folder: String, parentOf: (String) -> String?): Int =
    generateSequence(folder, parentOf).take(MAX_FOLDER_DEPTH).count()

/**
 * Hosts the gallery's screens with Material navigation motion, and lets back follow the system gesture.
 *
 * A screen only composes while it is the one being navigated to. While it leaves, or while the gesture peeks at it,
 * it is drawn from its last frame instead: its live content would already show the next screen's photos, and its
 * shared grid state must not be measured against them. Photos keeps that frame in [libraryFrame] so a back gesture
 * from another destination can show it before it composes again.
 */
@Composable
internal fun GalleryScreenHost(
    screen: GalleryScreen,
    parentOf: (String) -> String?,
    backEnabled: Boolean,
    onBack: () -> Unit,
    libraryFrame: GraphicsLayer,
    modifier: Modifier = Modifier,
    /** True while the caller is fading a destination switch itself (see FadeThrough); the host then swaps at once. */
    faded: () -> Boolean = { false },
    content: @Composable (GalleryScreen) -> Unit,
) {
    val reduceMotion = reduceGalleryMotion()
    val backState = remember { PredictiveBackState() }
    val (state, transition) = rememberNavigationTransition(screen, backState, reduceMotion, label = "gallery-screens")
    PredictiveBackNavigation(state, backState, previous = screen.parent(parentOf), key = { it }, reduceMotion = reduceMotion,
        enabled = backEnabled, onBack = onBack)
    val latestParentOf by rememberUpdatedState(parentOf)
    val screens = rememberSaveableStateHolder()
    val visited = remember { mutableSetOf<GalleryScreen>() }
    LaunchedEffect(transition.currentState, transition.targetState) {
        val settled = transition.currentState
        if (settled != transition.targetState) return@LaunchedEffect
        // Popped and sibling screens start fresh next time; the path to the current screen keeps its place.
        visited.filter { it != settled && it.tab == settled.tab && !settled.isWithin(it, latestParentOf) }.forEach {
            screens.removeState(it.saveKey)
            visited.remove(it)
        }
    }
    val slide = with(LocalDensity.current) { NavigationMotion.SHARED_AXIS_DP.dp.roundToPx() }
    val photoTransition = LocalPhotoTransition.current
    transition.AnimatedContent(
        modifier = modifier,
        transitionSpec = {
            val depth = { folder: String -> folderDepth(folder, latestParentOf) }
            val direction = galleryDirection(initialState, targetState, depth)
            val gesture = backState.phase != BackPhase.IDLE
            if (direction == NavigationDirection.LATERAL && !gesture && faded()) EnterTransition.None togetherWith ExitTransition.None
            else navigationTransform(direction, slide, gesture = gesture, reduceMotion = reduceMotion)
        },
    ) { shown ->
        val isLibrary = shown == GalleryScreen(GalleryTab.LIBRARY)
        val ownFrame = rememberGraphicsLayer()
        val frame = if (isLibrary) libraryFrame else ownFrame
        val target = transition.targetState == shown
        val settled = transition.currentState == transition.targetState
        val hasFrame = frame.size != IntSize.Zero
        // Photos waits for the settle to compose: its query is restored only once it is the current destination.
        val live = !hasFrame || (target && (settled || !isLibrary))
        Box(Modifier.fillMaxSize().predictiveBackPose(backState, shown).drawWithContent {
            if (live) {
                // Shared photos must draw directly while the viewer opens or closes, as AlbaApp's gallery frame does.
                // Record the frame once and draw that, rather than recording the whole screen twice every frame.
                if (photoTransition?.visibility?.transition?.isRunning != true) {
                    frame.record { this@drawWithContent.drawContent() }
                    drawLayer(frame)
                } else drawContent()
            } else drawLayer(frame)
        }) {
            if (live) {
                visited += shown
                screens.SaveableStateProvider(shown.saveKey) { content(shown) }
            }
        }
    }
}
