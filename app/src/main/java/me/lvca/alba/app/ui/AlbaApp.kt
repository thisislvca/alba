package me.lvca.alba.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.DiagnosticsConsent
import me.lvca.alba.app.R
import me.lvca.alba.app.account.PhoneStorageState
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.actions.MediaWork
import me.lvca.alba.app.gallery.GalleryState
import me.lvca.alba.app.messages.UiMessage
import me.lvca.alba.app.transfers.TransferState
import me.lvca.alba.app.ui.account.ICloudAccountScreen
import me.lvca.alba.app.ui.gallery.GalleryHome
import me.lvca.alba.app.ui.navigation.BackPhase
import me.lvca.alba.app.ui.navigation.NavigationDirection
import me.lvca.alba.app.ui.navigation.NavigationMotion
import me.lvca.alba.app.ui.navigation.PredictiveBackNavigation
import me.lvca.alba.app.ui.navigation.PredictiveBackState
import me.lvca.alba.app.ui.navigation.navigationTransform
import me.lvca.alba.app.ui.navigation.predictiveBackPose
import me.lvca.alba.app.ui.navigation.rememberNavigationTransition
import me.lvca.alba.app.ui.viewer.LocalPhotoTransition
import me.lvca.alba.app.ui.viewer.MediaDetailScreen
import me.lvca.alba.app.ui.viewer.PHOTO_TRANSITION_MILLIS
import me.lvca.alba.app.ui.viewer.PhotoTransitionScope
import me.lvca.alba.app.ui.viewer.reduceGalleryMotion
import me.lvca.alba.engine.companion.BatchAction
import me.lvca.alba.engine.model.GalleryMedia

/**
 * Where the app is. Derived from the account screen flag and the open photo, which the ViewModel keeps in saved
 * state, so the destination survives process death without saving it separately.
 */
internal sealed interface Destination {
    /** Names the destination's saved screen state and animated content. */
    val key: String

    data object Gallery : Destination { override val key = "gallery" }
    data object Account : Destination { override val key = "account" }

    /** The viewer, with the gallery's items at the time, so an outgoing viewer keeps its snapshot while it animates. */
    data class Media(val media: GalleryMedia, val items: List<GalleryMedia>) : Destination { override val key = MEDIA_KEY }

    companion object {
        fun of(session: SessionState, gallery: GalleryState): Destination = when {
            session.isAccountOpen -> Account
            gallery.selectedMedia != null -> Media(gallery.selectedMedia, gallery.items)
            else -> Gallery
        }
    }
}

private const val MEDIA_KEY = "media"

/** What the system back gesture does at the top level, before a screen's own back handling. */
internal enum class RootBack { CLEAR_SELECTION, CLOSE_ACCOUNT, CLOSE_MEDIA }

internal fun rootBack(destination: Destination, hasSelection: Boolean): RootBack? = when {
    hasSelection -> RootBack.CLEAR_SELECTION
    destination is Destination.Account -> RootBack.CLOSE_ACCOUNT
    destination is Destination.Media -> RootBack.CLOSE_MEDIA
    else -> null
}

@Composable
fun AlbaApp(
    gallery: GalleryState,
    session: SessionState,
    transfers: TransferState,
    phoneStorage: PhoneStorageState,
    mediaWork: MediaWork,
    previewRetryVersion: Int,
    message: UiMessage?,
    diagnostics: DiagnosticsConsent,
    actions: AlbaActions,
    onViewerLightBars: (Boolean) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val screenState = rememberSaveableStateHolder()
    val galleryLayer = rememberGraphicsLayer()

    val localizedMessage = message?.localized()
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(message, localizedMessage) {
        val text = localizedMessage ?: return@LaunchedEffect
        val undo = (message as? UiMessage.Undoable)?.undo
        if (undo == null) {
            snackbarHostState.showSnackbar(text)
            actions.dismissMessage()
            return@LaunchedEffect
        }
        val result = snackbarHostState.showSnackbar(UndoSnackbarVisuals(text, undoLabel))
        // Clear first: undoing shows its own result message, which must not be cleared with this one.
        actions.dismissMessage()
        if (result == SnackbarResult.ActionPerformed) actions.undo(undo)
    }

    if (mediaWork.sharing) AlertDialog(
        onDismissRequest = actions::cancelShare, title = { Text(stringResource(R.string.preparing_to_share)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text(stringResource(R.string.downloading_original_files_when_needed_live_photos_share_their_still_image_use_zip_ex))
        } }, confirmButton = { TextButton(onClick = actions::cancelShare) { Text(stringResource(R.string.cancel)) } })

    if (mediaWork.preparingOriginal) AlertDialog(
        onDismissRequest = actions::cancelShare, title = { Text(stringResource(R.string.viewer_preparing_original)) },
        text = { CircularProgressIndicator() },
        confirmButton = { TextButton(onClick = actions::cancelShare) { Text(stringResource(R.string.cancel)) } })

    val destination = Destination.of(session, gallery)
    val back = rootBack(destination, gallery.selection.isNotEmpty())
    BackHandler(enabled = back == RootBack.CLEAR_SELECTION || back == RootBack.CLOSE_MEDIA) {
        when (back) {
            RootBack.CLEAR_SELECTION -> actions.clearSelection()
            RootBack.CLOSE_MEDIA -> actions.closeMedia()
            RootBack.CLOSE_ACCOUNT, null -> Unit
        }
    }

    var viewerWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(destination.key) {
        if (destination is Destination.Media) viewerWasOpen = true
        // Discard only after an actual exit, not while the initial selection is restoring.
        if (destination is Destination.Gallery && viewerWasOpen) {
            screenState.removeState(MEDIA_KEY)
            viewerWasOpen = false
        }
    }
    // Account sits on top of the gallery or viewer, which is where closing it returns to.
    val beneath = if (destination !is Destination.Account) destination
        else gallery.selectedMedia?.let { Destination.Media(it, gallery.items) } ?: Destination.Gallery
    val reduceMotion = reduceGalleryMotion()
    val accountBack = remember { PredictiveBackState() }
    val (accountState, accountTransition) = rememberNavigationTransition(destination is Destination.Account, accountBack,
        reduceMotion, label = "account-navigation")
    PredictiveBackNavigation(accountState, accountBack, previous = false.takeIf { back == RootBack.CLOSE_ACCOUNT },
        key = { it }, reduceMotion = reduceMotion, onBack = actions::closeAccount)
    var galleryBottomSpace by remember { mutableStateOf(100.dp) }
    val slide = with(LocalDensity.current) { NavigationMotion.SHARED_AXIS_DP.dp.roundToPx() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            // Floating controls are inside the gallery rather than Scaffold.bottomBar.
            // Keep status messages above their measured height, including large text/selection.
            AlbaSnackbarHost(snackbarHostState, Modifier.imePadding().padding(bottom =
                if (destination is Destination.Gallery) galleryBottomSpace else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
        },
    ) { padding ->
        accountTransition.AnimatedContent(
            transitionSpec = {
                navigationTransform(if (targetState) NavigationDirection.FORWARD else NavigationDirection.BACK, slide,
                    gesture = accountBack.phase != BackPhase.IDLE, reduceMotion = reduceMotion)
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) { accountOpen ->
            Box(Modifier.fillMaxSize().predictiveBackPose(accountBack, accountOpen)) {
                if (accountOpen) {
                    screenState.SaveableStateProvider(Destination.Account.key) {
                        ICloudAccountScreen(
                            session = session,
                            transfers = transfers,
                            phoneStorage = phoneStorage,
                            mediaBusy = mediaWork.busy,
                            diagnostics = diagnostics,
                            actions = actions,
                        )
                    }
                } else {
                    // The photo layer: the gallery, and the viewer opening from and closing into it.
                    val navigation = updateTransition(beneath, label = "photo-navigation")
                    val shown = destination !is Destination.Account
                    SharedTransitionLayout {
                        navigation.AnimatedContent(
                            contentKey = { it.key },
                            transitionSpec = {
                                // Keep the gallery opaque underneath the viewer. Fading both screens exposes
                                // the scaffold between them and produces a pale flash.
                                if (targetState is Destination.Media) {
                                    fadeIn(tween(PHOTO_TRANSITION_MILLIS)).togetherWith(ExitTransition.None)
                                        .apply { targetContentZIndex = 1f }
                                } else {
                                    EnterTransition.None.togetherWith(fadeOut(tween(PHOTO_TRANSITION_MILLIS)))
                                        .apply { targetContentZIndex = 0f }
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        ) photo@{ target ->
                            CompositionLocalProvider(LocalPhotoTransition provides PhotoTransitionScope(
                                this@SharedTransitionLayout, this@photo, shown && target.key == beneath.key,
                                returningMediaId = (navigation.currentState as? Destination.Media)?.media?.id
                                    .takeIf { beneath is Destination.Gallery },
                            )) {
                                screenState.SaveableStateProvider(target.key) {
                                    when (target) {
                                        Destination.Gallery -> Box(Modifier.fillMaxSize().drawWithContent {
                                            // Keep the settled gallery for interactive dismissal, but let shared
                                            // elements draw directly during navigation so their return bounds survive.
                                            // Record it once and draw that, rather than recording the gallery twice every frame.
                                            if (!navigation.isRunning && destination is Destination.Gallery) {
                                                galleryLayer.record { this@drawWithContent.drawContent() }
                                                drawLayer(galleryLayer)
                                            } else drawContent()
                                        }) {
                                            GalleryHome(
                                                gallery = gallery,
                                                session = session,
                                                transfers = transfers,
                                                mediaBusy = mediaWork.busy,
                                                previewRetryVersion = previewRetryVersion,
                                                actions = actions,
                                                onBottomSpaceChanged = { galleryBottomSpace = it },
                                            )
                                        }
                                        Destination.Account -> Unit
                                        is Destination.Media -> {
                                            // AnimatedContent retains the outgoing snapshot until the image reaches its tile.
                                            val media = target.media
                                            val viewerItems = if (target.items.any { it.id == media.id }) target.items else listOf(media) + target.items
                                            val downloadingOriginal = transfers.batches.any { it.mediaId == media.id &&
                                                it.action == BatchAction.KEEP_OFFLINE.name && it.status.isPending }
                                            val stagingUpload = transfers.batches.any { it.mediaId == media.id &&
                                                it.action == BatchAction.UPLOAD.name && it.status.isPending }
                                            MediaDetailScreen(
                                                media = media,
                                                items = viewerItems,
                                                collections = gallery.collections,
                                                currentAlbumId = gallery.query.collectionId,
                                                accountState = session.accountState,
                                                uploadTransfer = transfers.uploadsByMediaId[media.id],
                                                isWorking = mediaWork.busy || session.accountBusy || downloadingOriginal || stagingUpload,
                                                isDownloadingOriginal = downloadingOriginal,
                                                actions = actions,
                                                onPageSettled = { if (destination is Destination.Media) actions.openMedia(it) },
                                                gallerySnapshot = galleryLayer,
                                                onViewerLightBars = onViewerLightBars,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
