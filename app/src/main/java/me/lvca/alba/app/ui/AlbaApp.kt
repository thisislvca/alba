package me.lvca.alba.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.lvca.alba.engine.companion.BatchAction
import me.lvca.alba.engine.model.GalleryMedia
import androidx.compose.material3.AlertDialog
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
import me.lvca.alba.app.ui.viewer.LocalPhotoTransition
import me.lvca.alba.app.ui.viewer.MediaDetailScreen
import me.lvca.alba.app.ui.viewer.PHOTO_TRANSITION_MILLIS
import me.lvca.alba.app.ui.viewer.PhotoTransitionScope

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
    LaunchedEffect(localizedMessage) {
        val text = localizedMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        actions.dismissMessage()
    }

    if (mediaWork.sharing) AlertDialog(
        onDismissRequest = actions::cancelShare, title = { Text(stringResource(R.string.preparing_to_share)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text(stringResource(R.string.downloading_original_files_when_needed_live_photos_share_their_still_image_use_zip_ex))
        } }, confirmButton = { TextButton(onClick = actions::cancelShare) { Text(stringResource(R.string.cancel)) } })

    val destination = Destination.of(session, gallery)
    val back = rootBack(destination, gallery.selection.isNotEmpty())
    BackHandler(enabled = back != null) {
        when (back) {
            RootBack.CLEAR_SELECTION -> actions.clearSelection()
            RootBack.CLOSE_ACCOUNT -> actions.closeAccount()
            RootBack.CLOSE_MEDIA -> actions.closeMedia()
            null -> Unit
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
    val navigation = updateTransition(destination, label = "photo-navigation")
    var galleryBottomSpace by remember { mutableStateOf(100.dp) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            // Floating controls are inside the gallery rather than Scaffold.bottomBar.
            // Keep status messages above their measured height, including large text/selection.
            SnackbarHost(snackbarHostState, Modifier.imePadding().padding(bottom =
                if (destination is Destination.Gallery) galleryBottomSpace else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
                Snackbar(it, modifier = Modifier.testTag("status-snackbar"))
            }
        },
    ) { padding ->
        SharedTransitionLayout {
            navigation.AnimatedContent(
                contentKey = { it.key },
                transitionSpec = {
                    if (initialState !is Destination.Account && targetState !is Destination.Account) {
                        // Keep the gallery opaque underneath the viewer. Fading both screens exposes
                        // the scaffold between them and produces a pale flash.
                        if (targetState is Destination.Media) {
                            fadeIn(tween(PHOTO_TRANSITION_MILLIS)).togetherWith(ExitTransition.None)
                                .apply { targetContentZIndex = 1f }
                        } else {
                            EnterTransition.None.togetherWith(fadeOut(tween(PHOTO_TRANSITION_MILLIS)))
                                .apply { targetContentZIndex = 0f }
                        }
                    } else {
                        val direction = if (targetState is Destination.Gallery) AnimatedContentTransitionScope.SlideDirection.Right
                            else AnimatedContentTransitionScope.SlideDirection.Left
                        (slideIntoContainer(direction, tween(260)) + fadeIn(tween(180)))
                            .togetherWith(slideOutOfContainer(direction, tween(260)) + fadeOut(tween(140)))
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) { target ->
                CompositionLocalProvider(LocalPhotoTransition provides PhotoTransitionScope(
                    this@SharedTransitionLayout, this@AnimatedContent, target.key == destination.key,
                    returningMediaId = (navigation.currentState as? Destination.Media)?.media?.id
                        .takeIf { destination is Destination.Gallery },
                )) {
                    screenState.SaveableStateProvider(target.key) {
                        when (target) {
                            Destination.Gallery -> Box(Modifier.fillMaxSize().drawWithContent {
                                // Keep the settled gallery for interactive dismissal, but let shared
                                // elements draw directly during navigation so their return bounds survive.
                                if (!navigation.isRunning && destination is Destination.Gallery) {
                                    galleryLayer.record { this@drawWithContent.drawContent() }
                                }
                                drawContent()
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
                            Destination.Account -> ICloudAccountScreen(
                                session = session,
                                transfers = transfers,
                                phoneStorage = phoneStorage,
                                mediaBusy = mediaWork.busy,
                                diagnostics = diagnostics,
                                actions = actions,
                            )
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
