package me.lvca.alba.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import me.lvca.alba.app.intake.IncomingRequest
import me.lvca.alba.app.intake.IncomingSession
import me.lvca.alba.app.intake.MAX_INCOMING_ITEMS
import me.lvca.alba.app.platform.ExportDocument
import me.lvca.alba.app.platform.OnboardingPreferences
import me.lvca.alba.app.platform.PendingExport
import me.lvca.alba.app.platform.UploadTarget
import me.lvca.alba.app.platform.deviceLibraryChanges
import me.lvca.alba.app.platform.hasDevicePhotoAccess
import me.lvca.alba.app.platform.hasLimitedPhotoAccess
import me.lvca.alba.app.platform.phoneTrashUris
import me.lvca.alba.app.platform.requestedDevicePhotoPermissions
import me.lvca.alba.app.platform.uploadNeedsMediaLocationPermission
import me.lvca.alba.app.transfers.BackupSetup
import me.lvca.alba.app.ui.AlbaActions
import me.lvca.alba.app.ui.AlbaApp
import me.lvca.alba.app.ui.InvitationActions
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.editor.PhotoEditorScreen
import me.lvca.alba.app.ui.intake.IncomingMediaScreen
import me.lvca.alba.app.ui.onboarding.WELCOME_CHOICES_PAGE
import me.lvca.alba.app.ui.onboarding.WelcomeScreen
import me.lvca.alba.app.ui.shared.SharedInvitations
import me.lvca.alba.engine.companion.BatchAction
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.GalleryQuery
import me.lvca.alba.engine.model.LocalMediaCategory
import me.lvca.alba.engine.model.SharedAlbumGeneration
import me.lvca.alba.engine.model.SharedCommand
import me.lvca.alba.engine.model.SystemConsentCallback
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus

/** A confirmation or choice the root shows over the app. Only one is open at a time. */
internal sealed interface RootDialog {
    /** The demo cannot read phone uploads silently: offer the sample or the photo picker. */
    data object DemoUpload : RootDialog
    data class ConfirmCloudTrash(val mediaIds: List<String>, val trashed: Boolean) : RootDialog
    data class ConfirmRemoveFromAlbum(val albumId: String, val mediaIds: List<String>) : RootDialog

    companion object {
        val Saver = Saver<RootDialog?, List<Any>>(
            save = { dialog ->
                when (dialog) {
                    DemoUpload -> listOf("demo-upload")
                    is ConfirmCloudTrash -> listOf("cloud-trash", dialog.trashed, ArrayList(dialog.mediaIds))
                    is ConfirmRemoveFromAlbum -> listOf("remove-from-album", dialog.albumId, ArrayList(dialog.mediaIds))
                    null -> null
                }
            },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                when (saved[0]) {
                    "demo-upload" -> DemoUpload
                    "cloud-trash" -> ConfirmCloudTrash(saved[2] as List<String>, saved[1] as Boolean)
                    "remove-from-album" -> ConfirmRemoveFromAlbum(saved[1] as String, saved[2] as List<String>)
                    else -> null
                }
            },
        )
    }
}

private val UploadTargetSaver = Saver<UploadTarget?, String>(
    save = { target -> when (target) { UploadTarget.Selection -> "selection"; is UploadTarget.Media -> "media:" + target.mediaId; null -> null } },
    restore = { saved -> if (saved == "selection") UploadTarget.Selection else UploadTarget.Media(saved.removePrefix("media:")) },
)

private val PendingExportSaver = Saver<PendingExport?, List<Any>>(
    save = { export -> export?.let { listOf(it.mediaId, it.livePair) } },
    restore = { saved -> PendingExport(saved[0] as String, saved[1] as Boolean) },
)

private fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * The activity's whole UI: onboarding, incoming shares and invitations, the photo editor, and the app itself. Owns the
 * platform side of user actions (activity results, permissions, the share sheet) and performs the ViewModel's
 * one-shot [AlbaEffect]s.
 */
@Composable
internal fun AlbaRoot(
    viewModel: AlbaViewModel,
    onboarding: OnboardingPreferences,
    incoming: IncomingSession,
    onIncomingChange: (IncomingSession) -> Unit,
) {
    var showWelcome by rememberSaveable { mutableStateOf(!onboarding.completed) }
    // Sign-in opened from the introduction: leaving it without an account returns there, not to an empty library.
    var connectingFromWelcome by rememberSaveable { mutableStateOf(false) }
    // Every root screen and dialog, including those shown before the app itself, uses Alba's theme.
    AlbaTheme(darkTheme = isSystemInDarkTheme()) {
        if (showWelcome && incoming.request == null) {
            WelcomeScreen(
                // Returning from sign-in lands on the page that offers it, next to the demo.
                initialPage = if (onboarding.completed) WELCOME_CHOICES_PAGE else 0,
                onConnect = {
                    onboarding.complete(); viewModel.account.connectAccount()
                    connectingFromWelcome = true; showWelcome = false
                },
                onDemo = { onboarding.complete(); viewModel.account.startDemo(); showWelcome = false },
            )
        } else {
            AlbaMain(viewModel, incoming, onIncomingChange, onAccountClosed = {
                if (connectingFromWelcome) {
                    connectingFromWelcome = false
                    if (viewModel.account.state.value.accountState !is ICloudAccountState.SignedIn) showWelcome = true
                }
            })
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun AlbaMain(
    viewModel: AlbaViewModel,
    incoming: IncomingSession,
    onIncomingChange: (IncomingSession) -> Unit,
    onAccountClosed: () -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val gallery by viewModel.gallery.state.collectAsStateWithLifecycle()
    val session by viewModel.account.state.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.state.collectAsStateWithLifecycle()
    val phoneStorage by viewModel.phoneStorage.state.collectAsStateWithLifecycle()
    val mediaWork by viewModel.mediaWork.collectAsStateWithLifecycle()
    val previewRetryVersion by viewModel.previews.retryVersion.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnosticsConsent.collectAsStateWithLifecycle()
    val currentIncoming by rememberUpdatedState(incoming)
    val shareTitle by rememberUpdatedState(stringResource(R.string.share))

    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by rememberSaveable(stateSaver = RootDialog.Saver) { mutableStateOf(null) }
    var pendingExport by rememberSaveable(stateSaver = PendingExportSaver) { mutableStateOf(null) }
    var pendingUpload by rememberSaveable(stateSaver = UploadTargetSaver) { mutableStateOf(null) }
    // Opening the account to connect hides an incoming request until the account screen closes again.
    var incomingSuspended by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(session.isAccountOpen) { if (!session.isAccountOpen) incomingSuspended = false }

    var hasDevicePhotoAccess by remember { mutableStateOf(hasDevicePhotoAccess(Build.VERSION.SDK_INT, context::granted)) }
    fun recheckDevicePhotoAccess() { hasDevicePhotoAccess = hasDevicePhotoAccess(Build.VERSION.SDK_INT, context::granted) }
    LaunchedEffect(hasDevicePhotoAccess) {
        if (hasDevicePhotoAccess) lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            deviceLibraryChanges(context.contentResolver).debounce(350).collect { viewModel.sync.deviceLibraryChanged() }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        recheckDevicePhotoAccess()
        viewModel.setDeviceMediaAccess(hasDevicePhotoAccess, hasLimitedPhotoAccess(Build.VERSION.SDK_INT, context::granted))
        viewModel.sync.refresh()
    }
    LaunchedEffect(hasDevicePhotoAccess) {
        viewModel.setDeviceMediaAccess(hasDevicePhotoAccess, hasLimitedPhotoAccess(Build.VERSION.SDK_INT, context::granted))
    }

    val devicePhotoPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        recheckDevicePhotoAccess()
    }
    val backupPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.transfers.backupPermissionsAnswered()
        recheckDevicePhotoAccess()
    }
    val phoneTrash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.phoneTrashChanged()
    }
    val verifiedTrash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.transfers.verifiedTrashAnswered(when (result.resultCode) {
            Activity.RESULT_OK -> SystemConsentCallback.APPROVED
            Activity.RESULT_CANCELED -> SystemConsentCallback.CANCELED
            else -> SystemConsentCallback.DENIED
        })
    }
    val exportDocument = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val export = pendingExport
        val destination = result.data?.data
        pendingExport = null
        if (result.resultCode == Activity.RESULT_OK && export != null && destination != null) {
            viewModel.exports.exportDocument(export.mediaId, destination, export.livePair)
        }
    }
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_INCOMING_ITEMS)) { uris ->
        uploadPicked(context, viewModel, uris)
    }
    val mediaLocationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val target = pendingUpload
        pendingUpload = null
        if (granted && target != null) startUpload(viewModel, target)
    }

    val platform = remember(viewModel, context) {
        object : PlatformRequests {
            override fun requestDevicePhotos() =
                devicePhotoPermissions.launch(requestedDevicePhotoPermissions(Build.VERSION.SDK_INT).toTypedArray())

            override fun pickUpload() {
                if (viewModel.account.state.value.accountState == ICloudAccountState.Demo) dialog = RootDialog.DemoUpload
                else uploadPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }

            override fun upload(target: UploadTarget) {
                val accountState = viewModel.account.state.value.accountState
                if (uploadNeedsMediaLocationPermission(accountState, Build.VERSION.SDK_INT, context.granted(Manifest.permission.ACCESS_MEDIA_LOCATION))) {
                    pendingUpload = target
                    mediaLocationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                } else startUpload(viewModel, target)
            }

            override fun trashOnPhone(items: List<GalleryMedia>, restore: Boolean) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || items.isEmpty()) return
                val uris = phoneTrashUris(items)?.map(Uri::parse) ?: return viewModel.localOperationFailed()
                runCatching {
                    phoneTrash.launch(IntentSenderRequest.Builder(MediaStore.createTrashRequest(context.contentResolver, uris, !restore).intentSender).build())
                }.onFailure { viewModel.localOperationFailed() }
            }

            override fun exportDocument(media: GalleryMedia, livePair: Boolean) {
                pendingExport = PendingExport(media.id, livePair)
                val document = ExportDocument.forMedia(media, livePair)
                exportDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = document.mimeType
                    putExtra(Intent.EXTRA_TITLE, document.title)
                })
            }

            override fun edit(media: GalleryMedia) { editingId = media.id }
            override fun confirm(next: RootDialog) { dialog = next }
        }
    }
    val accountClosed by rememberUpdatedState(onAccountClosed)
    val actions = remember(viewModel, platform) { AlbaRootActions(viewModel, platform, onAccountClosed = { accountClosed() }) }

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Performing and acknowledging do not suspend, so a cancelled collector either handled the request or
            // leaves it queued for the next one.
            viewModel.effects.collect { pending ->
                try {
                    when (val effect = pending.effect) {
                        is AlbaEffect.Share -> runCatching {
                            context.startActivity(Intent.createChooser(effect.intent, shareTitle))
                        }.onFailure { viewModel.exports.shareFailed() }
                        is AlbaEffect.ConfirmVerifiedTrash -> verifiedTrash.launch(IntentSenderRequest.Builder(effect.request).build())
                        is AlbaEffect.RequestBackupPermissions -> backupPermissions.launch(effect.permissions.toTypedArray())
                    }
                } finally {
                    viewModel.effectHandled(pending)
                }
            }
        }
    }

    fun closeIncoming() {
        val returnToCaller = currentIncoming.returnToCaller
        onIncomingChange(currentIncoming.copy(request = null))
        if (returnToCaller) activity?.finish()
    }
    fun connectForIncoming() { incomingSuspended = true; viewModel.account.openAccount() }

    (incoming.request as? IncomingRequest.Media)?.takeUnless { incomingSuspended }?.let { request ->
        IncomingMediaScreen(request, session.accountState, message,
            close = ::closeIncoming,
            connect = ::connectForIncoming,
            upload = { uris ->
                onIncomingChange(currentIncoming.copy(returnToCaller = false))
                uploadPicked(context, viewModel, uris)
            })
    }
    (incoming.request as? IncomingRequest.Invitation)?.takeUnless { incomingSuspended }?.let { request ->
        IncomingInvitation(request.link, session.accountState, actions, ::closeIncoming, ::connectForIncoming)
    }

    editingId?.let { id -> gallery.catalogItems.firstOrNull { it.id == id }?.let { media ->
        PhotoEditorScreen(media, viewModel.photoEdits::source, close = { editingId = null }, saved = { uri ->
            viewModel.sync.refresh()
            onIncomingChange(IncomingSession(IncomingRequest.Media(listOf(uri.toString()), savedCopy = true), returnToCaller = false))
        })
    } }

    if (dialog == RootDialog.DemoUpload) AlertDialog(
        onDismissRequest = { dialog = null },
        title = { Text(stringResource(R.string.demo_upload_title)) },
        text = { Text(stringResource(R.string.demo_upload_disclosure)) },
        confirmButton = { TextButton(onClick = { dialog = null; viewModel.transfers.uploadDemoSample() }) {
            Text(stringResource(R.string.demo_upload_sample))
        } },
        dismissButton = { TextButton(onClick = {
            dialog = null
            uploadPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }) { Text(stringResource(R.string.choose_photos_to_include)) } },
    )

    val darkTheme = isSystemInDarkTheme()
    var viewerLightBars by remember { mutableStateOf(true) }
    val lightBars = if (gallery.selectedMedia != null && !session.isAccountOpen) viewerLightBars else !darkTheme
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }
    AlbaApp(
        gallery = gallery,
        session = session,
        transfers = transfers,
        phoneStorage = phoneStorage,
        mediaWork = mediaWork,
        previewRetryVersion = previewRetryVersion,
        message = message,
        diagnostics = diagnostics,
        actions = actions,
        onViewerLightBars = { viewerLightBars = it },
    )
    when (val current = dialog) {
        is RootDialog.ConfirmCloudTrash -> ConfirmRemoval(
            title = if (current.trashed) R.string.trash_in_icloud else R.string.restore_from_icloud,
            body = if (current.trashed) R.plurals.cloud_trash_confirmation else R.plurals.cloud_restore_confirmation,
            count = current.mediaIds.size,
            dismiss = { dialog = null },
            confirm = { viewModel.edits.setCloudTrashed(current.mediaIds, current.trashed); dialog = null },
        )
        is RootDialog.ConfirmRemoveFromAlbum -> ConfirmRemoval(
            title = R.string.remove_from_album,
            body = R.plurals.remove_album_confirmation,
            count = current.mediaIds.size,
            dismiss = { dialog = null },
            confirm = { viewModel.edits.removeFromAlbum(current.albumId, current.mediaIds); dialog = null },
        )
        RootDialog.DemoUpload, null -> Unit
    }
    transfers.backupSetup?.takeUnless { it.awaitingPermissions }?.let { setup ->
        BackupDisclosure(setup, accept = viewModel.transfers::acceptBackupDisclosure, dismiss = viewModel.transfers::dismissBackupSetup)
    }
}

/** Uploads media the user picked or shared, keeping read access for the durable upload queue. */
private fun uploadPicked(context: Context, viewModel: AlbaViewModel, uris: List<Uri>) {
    uris.forEach { uri -> runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    viewModel.transfers.uploadPicked(uris)
}

private fun startUpload(viewModel: AlbaViewModel, target: UploadTarget) = when (target) {
    UploadTarget.Selection -> viewModel.transfers.runBatch(BatchAction.UPLOAD)
    is UploadTarget.Media -> viewModel.transfers.requestUpload(target.mediaId)
}

@Composable
private fun IncomingInvitation(
    link: String,
    accountState: ICloudAccountState,
    invitations: InvitationActions,
    close: () -> Unit,
    connect: () -> Unit,
) {
    val account = accountState as? ICloudAccountState.SignedIn
    if (link.isBlank()) AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.shared_invitations)) },
        text = { Text(stringResource(R.string.shared_invalid_link)) },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } })
    else if (account?.status == SessionStatus.VERIFIED) {
        key(account.appleId, link) {
            SharedInvitations(invitations,
                dismiss = close, initialUrl = link, invalidLink = link.isBlank())
        }
    } else AlertDialog(onDismissRequest = close,
        title = { Text(stringResource(R.string.shared_invitations)) },
        text = { Text(stringResource(R.string.shared_invitation_sign_in)) },
        confirmButton = { TextButton(onClick = connect) { Text(stringResource(R.string.incoming_connect)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun ConfirmRemoval(title: Int, body: Int, count: Int, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(title)) },
        text = { Text(pluralStringResource(body, count, count)) },
        confirmButton = { TextButton(onClick = confirm) { Text(stringResource(title)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun BackupDisclosure(setup: BackupSetup, accept: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.back_up_new_photos)) },
        text = {
            Text(if (setup.demo) stringResource(R.string.demo_backup_disclosure)
                else stringResource(R.string.backup_disclosure, setup.destinationLabel))
        },
        confirmButton = { Button(onClick = accept) { Text(stringResource(R.string.welcome_continue)) } },
        dismissButton = { OutlinedButton(onClick = dismiss) { Text(stringResource(R.string.not_now)) } },
    )
}

/** The platform side of user actions: activity results, permission prompts and root dialogs. */
internal interface PlatformRequests {
    fun requestDevicePhotos()
    fun pickUpload()
    fun upload(target: UploadTarget)
    fun trashOnPhone(items: List<GalleryMedia>, restore: Boolean)
    fun exportDocument(media: GalleryMedia, livePair: Boolean)
    fun edit(media: GalleryMedia)
    fun confirm(next: RootDialog)
}

/** Routes every screen action to its state holder, or to [platform] when it needs the activity first. */
private class AlbaRootActions(
    private val viewModel: AlbaViewModel,
    private val platform: PlatformRequests,
    private val onAccountClosed: () -> Unit,
) : AlbaActions {

    private val gallery get() = viewModel.gallery
    private val transfers get() = viewModel.transfers
    private val edits get() = viewModel.edits
    private val shared get() = viewModel.sharedAlbums
    private val account get() = viewModel.account

    override fun dismissMessage() = viewModel.dismissMessage()

    override fun changeQuery(query: GalleryQuery) = gallery.setQuery(query)
    override fun toggleSelection(mediaId: String) = gallery.toggleSelection(mediaId)
    override fun selectItems(mediaIds: Set<String>, selected: Boolean) = gallery.selectItems(mediaIds, selected)
    override fun selectAllShown() = gallery.selectAllShown()
    override fun clearSelection() = gallery.clearSelection()
    override fun openMedia(mediaId: String) = gallery.openMedia(mediaId)
    override fun requestPreview(mediaId: String) = viewModel.previews.request(mediaId)
    override fun prefetchPreviews(mediaIds: List<String>) = viewModel.previews.prefetch(mediaIds)
    override fun refresh() = viewModel.sync.refresh()
    override fun requestDevicePhotos() = platform.requestDevicePhotos()
    override fun openAccount() = account.openAccount()

    override fun setFavorite(mediaId: String, favorite: Boolean) = edits.setFavorite(mediaId, favorite)
    override fun setFavorites(mediaIds: List<String>, favorite: Boolean) = edits.setFavorites(mediaIds, favorite)
    override fun createAlbum(name: String) = edits.createAlbum(name)
    override fun createAlbumWithPhotos(name: String, mediaIds: List<String>) = edits.createAlbumWithPhotos(name, mediaIds)
    override fun renameAlbum(albumId: String, name: String) = edits.renameAlbum(albumId, name)
    override fun deleteAlbum(albumId: String) = edits.deleteAlbum(albumId)
    override fun addToAlbum(albumId: String, mediaIds: List<String>) = edits.addToAlbum(albumId, mediaIds)
    override fun confirmRemoveFromAlbum(albumId: String, mediaIds: List<String>) =
        if (mediaIds.isNotEmpty()) platform.confirm(RootDialog.ConfirmRemoveFromAlbum(albumId, mediaIds.toList())) else Unit
    override fun confirmCloudTrash(mediaIds: List<String>, trashed: Boolean) =
        if (mediaIds.isNotEmpty()) platform.confirm(RootDialog.ConfirmCloudTrash(mediaIds.toList(), trashed)) else Unit
    override fun trashOnPhone(items: List<GalleryMedia>, restore: Boolean) = platform.trashOnPhone(items, restore)
    override fun contributeToSharedAlbum(albumId: String, mediaIds: List<String>) = shared.contributeToSharedAlbum(albumId, mediaIds)
    override fun createSharedAlbum(name: String, generation: SharedAlbumGeneration) = shared.createSharedAlbum(name, generation)

    override fun runBatch(action: BatchAction) =
        if (action == BatchAction.UPLOAD) platform.upload(UploadTarget.Selection) else transfers.runBatch(action)
    override fun retryBatch(batchId: String) = transfers.retryBatch(batchId)
    override fun cancelDownloadBatch(batchId: String) = transfers.cancelDownloadBatch(batchId)
    override fun pickUpload() = platform.pickUpload()
    override fun requestUpload(mediaId: String) = platform.upload(UploadTarget.Media(mediaId))
    override fun continuePastUnresolved(transferId: TransferId) = transfers.continuePastUnresolved(transferId)
    override fun share(items: List<GalleryMedia>) = viewModel.exports.share(items)
    override fun cancelShare() = viewModel.exports.cancelShare()

    override fun closeMedia() = gallery.closeMedia()
    override suspend fun loadViewer(mediaId: String) = gallery.loadViewer(mediaId)
    override suspend fun openPlayback(mediaId: String, position: Long, length: Long) = gallery.openPlayback(mediaId, position, length)
    override fun edit(media: GalleryMedia) = platform.edit(media)
    override fun saveToGallery(media: GalleryMedia) = viewModel.exports.saveToGallery(media)
    override fun exportOriginal(media: GalleryMedia) = platform.exportDocument(media, livePair = false)
    override fun exportLivePhoto(media: GalleryMedia) = platform.exportDocument(media, livePair = true)
    override fun keepOriginalOffline(mediaId: String) = transfers.keepOriginalOffline(mediaId)
    override fun removeCachedOriginal(mediaId: String) = transfers.removeCachedOriginal(mediaId)
    override fun cancelOfflineDownload() = transfers.cancelOfflineDownload()
    override fun moveVerifiedPhotoToTrash(mediaId: String) = transfers.requestVerifiedTrash(mediaId)

    override fun closeAccount() { account.closeAccount(); onAccountClosed() }
    override fun signIn(appleId: String, password: String) = account.signIn(appleId, password)
    override fun submitTwoFactor(code: String) = account.submitTwoFactor(code)
    override fun resendTwoFactor() = account.resendTwoFactor()
    override fun signOut() = account.signOut()
    override fun checkConnection() = account.checkConnection()
    override fun retryRestore() = viewModel.sync.refresh()
    override fun enableAutomaticBackup() = transfers.beginAutomaticBackup()
    override fun disableAutomaticBackup() = transfers.disableAutomaticBackup()
    override fun demoBackupAction(transferId: TransferId) = transfers.continuePastUnresolved(transferId)
    override fun setDiagnosticsEnabled(enabled: Boolean) = viewModel.setDiagnosticsEnabled(enabled)
    override fun checkPhoneStorage() = viewModel.phoneStorage.checkPhoneStorage()
    override fun clearLocalMedia(category: LocalMediaCategory) = viewModel.phoneStorage.clearLocalMedia(category)

    override suspend fun sharedActivity(albumId: String, rank: Int) = shared.sharedActivity(albumId, rank)
    override suspend fun sharedPostDiscussion(postId: String) = shared.sharedPostDiscussion(postId)
    override suspend fun sharedPostPhotos(postId: String) = shared.sharedPostPhotos(postId)
    override suspend fun sharedManagement(albumId: String) = shared.sharedManagement(albumId)
    override suspend fun sharedDiscussion(id: String) = shared.sharedDiscussion(id)
    override suspend fun changeSharedAlbum(albumId: String, command: SharedCommand) = shared.changeSharedAlbum(albumId, command)
    override suspend fun pendingSharedInvitations() = shared.pendingSharedInvitations()
    override suspend fun resolveSharedInvitation(url: String) = shared.resolveSharedInvitation(url)
    override suspend fun respondSharedInvitation(invitationId: String, accept: Boolean) = shared.respondSharedInvitation(invitationId, accept)
}
