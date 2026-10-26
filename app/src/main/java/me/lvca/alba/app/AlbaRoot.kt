package me.lvca.alba.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.print.PrintHelper
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import me.lvca.alba.app.intake.IncomingRequest
import me.lvca.alba.app.intake.IncomingSession
import me.lvca.alba.app.intake.MAX_INCOMING_ITEMS
import me.lvca.alba.app.intake.albaComponents
import me.lvca.alba.app.messages.UndoAction
import me.lvca.alba.app.platform.ExportDocument
import me.lvca.alba.app.platform.MobileDataChoice
import me.lvca.alba.app.platform.OnboardingPreferences
import me.lvca.alba.app.platform.PendingExport
import me.lvca.alba.app.platform.PhotoPromptPreferences
import me.lvca.alba.app.platform.UploadTarget
import me.lvca.alba.app.platform.appSettingsIntent
import me.lvca.alba.app.platform.deviceLibraryChanges
import me.lvca.alba.app.platform.externalUseIntent
import me.lvca.alba.app.platform.hasDevicePhotoAccess
import me.lvca.alba.app.platform.hasLimitedPhotoAccess
import me.lvca.alba.app.platform.notBackedUpCount
import me.lvca.alba.app.platform.phoneTrashUris
import me.lvca.alba.app.platform.photoPromptBlocked
import me.lvca.alba.app.platform.requestedDevicePhotoPermissions
import me.lvca.alba.app.platform.uploadNeedsMediaLocationPermission
import me.lvca.alba.app.transfers.BackupSetup
import me.lvca.alba.app.ui.AlbaActions
import me.lvca.alba.app.ui.AlbaApp
import me.lvca.alba.app.ui.ExternalUse
import me.lvca.alba.app.ui.InvitationActions
import me.lvca.alba.app.ui.account.ProfilePage
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.editor.PhotoEditorScreen
import me.lvca.alba.app.ui.gallery.LocalBackupBadges
import me.lvca.alba.app.ui.gallery.LocalPreviewStorageLow
import me.lvca.alba.app.ui.gallery.backupBadges
import me.lvca.alba.app.ui.intake.IncomingMediaScreen
import me.lvca.alba.app.ui.onboarding.WELCOME_CHOICES_PAGE
import me.lvca.alba.app.ui.onboarding.WelcomeScreen
import me.lvca.alba.app.ui.shared.LocalNotificationAccess
import me.lvca.alba.app.ui.shared.LocalSharedActivity
import me.lvca.alba.app.ui.shared.NotificationAccess
import me.lvca.alba.app.ui.shared.SharedInvitations
import me.lvca.alba.app.work.canNotify
import me.lvca.alba.engine.companion.BatchAction
import me.lvca.alba.engine.model.BackupSettings
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.GalleryQuery
import me.lvca.alba.engine.model.LocalMediaCategory
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.engine.model.SharedAlbumGeneration
import me.lvca.alba.engine.model.SharedCommand
import me.lvca.alba.engine.model.SharedUploadFile
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

    /** Moving phone items to Android's trash when some of them are not backed up to iCloud. */
    data class ConfirmPhoneTrash(val mediaIds: List<String>, val notBackedUp: Int) : RootDialog

    /** One confirmation for phone and iCloud items trashed together; each goes to its own trash. */
    data class ConfirmMixedTrash(val cloudIds: List<String>, val phoneIds: List<String>) : RootDialog

    /** Explains photo location access before Android asks for it, ahead of an upload. */
    data class MediaLocation(val upload: UploadTarget) : RootDialog

    companion object {
        val Saver = Saver<RootDialog?, List<Any>>(
            save = { dialog ->
                when (dialog) {
                    DemoUpload -> listOf("demo-upload")
                    is ConfirmCloudTrash -> listOf("cloud-trash", dialog.trashed, ArrayList(dialog.mediaIds))
                    is ConfirmRemoveFromAlbum -> listOf("remove-from-album", dialog.albumId, ArrayList(dialog.mediaIds))
                    is ConfirmPhoneTrash -> listOf("phone-trash", dialog.notBackedUp, ArrayList(dialog.mediaIds))
                    is ConfirmMixedTrash -> listOf("mixed-trash", ArrayList(dialog.cloudIds), ArrayList(dialog.phoneIds))
                    is MediaLocation -> listOf("media-location", when (val target = dialog.upload) {
                        UploadTarget.Selection -> ""
                        is UploadTarget.Media -> target.mediaId
                    })
                    null -> null
                }
            },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                when (saved[0]) {
                    "demo-upload" -> DemoUpload
                    "cloud-trash" -> ConfirmCloudTrash(saved[2] as List<String>, saved[1] as Boolean)
                    "remove-from-album" -> ConfirmRemoveFromAlbum(saved[1] as String, saved[2] as List<String>)
                    "phone-trash" -> ConfirmPhoneTrash(saved[2] as List<String>, saved[1] as Int)
                    "mixed-trash" -> ConfirmMixedTrash(saved[1] as List<String>, saved[2] as List<String>)
                    "media-location" -> MediaLocation((saved[1] as String).takeIf(String::isNotEmpty)
                        ?.let(UploadTarget::Media) ?: UploadTarget.Selection)
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

private const val PERMISSION_GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

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
    // Signing out returns to the introduction's choices rather than dropping silently into an empty library.
    val returnToWelcome by viewModel.returnToWelcome.collectAsStateWithLifecycle()
    LaunchedEffect(returnToWelcome) {
        if (returnToWelcome) {
            connectingFromWelcome = false
            showWelcome = true
            viewModel.welcomeShown()
        }
    }
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
    val photoPrompt = remember(context) { PhotoPromptPreferences(context) }
    val useAsTitle = stringResource(R.string.viewer_use_as)
    val openWithTitle = stringResource(R.string.viewer_open_with)
    val editInTitle = stringResource(R.string.viewer_edit_in)
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val gallery by viewModel.gallery.state.collectAsStateWithLifecycle()
    val session by viewModel.account.state.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.state.collectAsStateWithLifecycle()
    val phoneStorage by viewModel.phoneStorage.state.collectAsStateWithLifecycle()
    val mediaWork by viewModel.mediaWork.collectAsStateWithLifecycle()
    val previewRetryVersion by viewModel.previews.retryVersion.collectAsStateWithLifecycle()
    val previewStorageLow by viewModel.previews.storageLow.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnosticsConsent.collectAsStateWithLifecycle()
    val sharedActivity by viewModel.sharedAlbums.activity.collectAsStateWithLifecycle()
    val currentIncoming by rememberUpdatedState(incoming)
    val shareTitle by rememberUpdatedState(stringResource(R.string.share))

    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by rememberSaveable(stateSaver = RootDialog.Saver) { mutableStateOf(null) }
    var pendingExport by rememberSaveable(stateSaver = PendingExportSaver) { mutableStateOf(null) }
    var pendingUpload by rememberSaveable(stateSaver = UploadTargetSaver) { mutableStateOf(null) }
    // A manual transfer waiting while Alba asks about notifications or mobile data first.
    var pendingTransfer by rememberSaveable(stateSaver = PendingTransfer.Saver) { mutableStateOf(null) }
    var transferPrompt by rememberSaveable { mutableStateOf<TransferPrompt?>(null) }
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

    val devicePhotoPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        recheckDevicePhotoAccess()
        val requested = requestedDevicePhotoPermissions(Build.VERSION.SDK_INT).first()
        photoPrompt.blocked = photoPromptBlocked(anyGranted = results.values.any { it } || hasDevicePhotoAccess,
            showRationale = activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, requested) } ?: true)
    }
    val backupPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.transfers.backupPermissionsAnswered()
        recheckDevicePhotoAccess()
    }
    val phoneTrash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.phoneTrashAnswered(result.resultCode == Activity.RESULT_OK)
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
    // Whatever Android answers, the transfer the user started goes ahead. Saved, with the held transfer, so the answer
    // still starts it after a rotation or process death during Android's dialog.
    var awaitingNotificationAnswer by rememberSaveable { mutableStateOf(false) }
    var notificationAnswered by remember { mutableStateOf(false) }
    // The Shared Albums switch and first-visit prompt ask through the same launcher. Saved, so an answer that arrives
    // after a rotation still reaches the setting.
    var awaitingSharedNotificationAnswer by rememberSaveable { mutableStateOf(false) }
    var notificationsGranted by remember { mutableStateOf(canNotify(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notificationsGranted = canNotify(context) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsGranted = canNotify(context)
        if (awaitingSharedNotificationAnswer) {
            awaitingSharedNotificationAnswer = false
            viewModel.sharedAlbums.setNotifications(notificationsGranted)
        }
        if (awaitingNotificationAnswer) notificationAnswered = true
    }
    val notificationAccess = remember(notificationsGranted, notificationPermission) {
        val openSettings = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
            Unit
        }
        NotificationAccess(notificationsGranted, request = {
            // Asked here, so the first manual transfer or backup does not ask again.
            viewModel.transfers.backup.notificationsAsked()
            if (!notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                awaitingSharedNotificationAnswer = true
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else if (!notificationsGranted) openSettings() else viewModel.sharedAlbums.setNotifications(true)
        }, openSettings = openSettings)
    }
    val mediaLocationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val target = pendingUpload
        pendingUpload = null
        if (granted && target != null) startUpload(viewModel, target)
    }

    val platform = remember(viewModel, context) {
        object : PlatformRequests {
            override fun requestDevicePhotos() {
                // Android no longer shows its prompt after two denials; its settings page is the only way left.
                if (photoPrompt.blocked && !hasDevicePhotoAccess(Build.VERSION.SDK_INT, context::granted)) {
                    runCatching { context.startActivity(appSettingsIntent(context.packageName)) }
                        .onSuccess { Toast.makeText(context, R.string.phone_photo_settings_hint, Toast.LENGTH_LONG).show() }
                        .onFailure { viewModel.localOperationFailed() }
                } else devicePhotoPermissions.launch(requestedDevicePhotoPermissions(Build.VERSION.SDK_INT).toTypedArray())
            }

            override fun pickUpload() {
                if (viewModel.account.state.value.accountState == ICloudAccountState.Demo) dialog = RootDialog.DemoUpload
                else uploadPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }

            override fun beforeTransfer(pending: PendingTransfer) {
                // The demo sends nothing, so it has nothing to ask.
                if (viewModel.account.state.value.accountState == ICloudAccountState.Demo) return run(pending)
                pendingTransfer = pending
                askNext(after = null)
            }

            override fun askNext(after: TransferPrompt?) {
                val pending = pendingTransfer ?: return
                val next = nextTransferPrompt(
                    askNotifications = viewModel.transfers.backup.shouldAskForNotifications(),
                    network = viewModel.account.state.value.network,
                    choice = viewModel.transfers.backup.mobileDataChoice,
                    after = after,
                )
                transferPrompt = next
                if (next != null) return
                pendingTransfer = null
                when (viewModel.transfers.backup.mobileDataChoice) {
                    MobileDataChoice.USE -> viewModel.transfers.backup.chooseMobileData(useMobileData = true, remember = false)
                    MobileDataChoice.WAIT -> viewModel.transfers.backup.chooseMobileData(useMobileData = false, remember = false)
                    // Asked just now, or not on mobile data: the answer, if any, is already saved.
                    MobileDataChoice.ASK -> if (after != TransferPrompt.MOBILE_DATA) {
                        viewModel.transfers.backup.chooseMobileData(useMobileData = true, remember = false)
                    }
                }
                run(pending)
            }

            override fun requestNotifications() {
                viewModel.transfers.backup.notificationsAsked()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    awaitingNotificationAnswer = true
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else askNext(TransferPrompt.NOTIFICATIONS)
            }

            private fun run(pending: PendingTransfer) = when (pending) {
                PendingTransfer.PickUpload -> pickUpload()
                is PendingTransfer.Upload -> upload(pending.target)
                is PendingTransfer.Batch -> viewModel.transfers.runBatch(pending.action)
                is PendingTransfer.KeepOffline -> viewModel.transfers.keepOriginalOffline(pending.mediaId)
                is PendingTransfer.Retry -> viewModel.transfers.retryTransfer(TransferId(pending.transferId))
                PendingTransfer.RetryAll -> viewModel.transfers.retryAllTransfers()
                is PendingTransfer.FreeUp -> viewModel.transfers.backup.startFreeUpSpace(pending.mediaIds,
                    onMobileData = viewModel.account.state.value.network?.let { it.online && it.metered } == true)
            }

            override fun upload(target: UploadTarget) {
                val accountState = viewModel.account.state.value.accountState
                if (uploadNeedsMediaLocationPermission(accountState, context.granted(Manifest.permission.ACCESS_MEDIA_LOCATION))) {
                    dialog = RootDialog.MediaLocation(target)
                } else startUpload(viewModel, target)
            }

            override fun trashOnPhone(items: List<GalleryMedia>, restore: Boolean) {
                if (items.isEmpty()) return
                val uris = phoneTrashUris(items) ?: return viewModel.phoneTrashFailed()
                // Say so before Android's own request when some of these exist only on this phone.
                val notBackedUp = if (restore) 0 else notBackedUpCount(items, viewModel.transfers.state.value.uploadsByMediaId)
                if (notBackedUp > 0) dialog = RootDialog.ConfirmPhoneTrash(items.map { it.id }, notBackedUp)
                else requestPhoneTrash(uris, restore)
            }

            override fun confirmPhoneTrash(mediaIds: List<String>) {
                val items = viewModel.gallery.current.catalogItems.filter { it.id in mediaIds }
                if (items.size != mediaIds.size) return viewModel.phoneTrashFailed()
                requestPhoneTrash(phoneTrashUris(items) ?: return viewModel.phoneTrashFailed(), restore = false)
            }

            override fun restoreOnPhone(contentUris: List<String>) = requestPhoneTrash(contentUris, restore = true)

            private fun requestPhoneTrash(contentUris: List<String>, restore: Boolean) {
                if (contentUris.isEmpty()) return
                viewModel.phoneTrashRequested(contentUris, restore)
                runCatching {
                    val request = MediaStore.createTrashRequest(context.contentResolver, contentUris.map(Uri::parse), !restore)
                    phoneTrash.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }.onFailure { viewModel.phoneTrashFailed() }
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
                        is AlbaEffect.UseElsewhere -> runCatching {
                            val intent = externalUseIntent(effect.use, effect.uri, effect.mimeType)
                            if (intent == null) {
                                PrintHelper(requireNotNull(activity)).apply { scaleMode = PrintHelper.SCALE_MODE_FIT }
                                    .printBitmap(effect.name, effect.uri)
                            } else context.startActivity(Intent.createChooser(intent, when (effect.use) {
                                ExternalUse.USE_AS -> useAsTitle
                                ExternalUse.EDIT_IN -> editInTitle
                                else -> openWithTitle
                            }).addFlags(intent.flags and PERMISSION_GRANT_FLAGS))
                        }.onFailure { viewModel.exports.useElsewhereFailed(effect.use) }
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
            },
            sharedAlbums = gallery.collections.filter { it.shared?.canContribute == true },
            addToShared = { albumId, items, caption, done ->
                onIncomingChange(currentIncoming.copy(returnToCaller = false))
                val resolver = context.contentResolver
                val files = items.map { item ->
                    SharedUploadFile(item.name) { output -> requireNotNull(resolver.openInputStream(item.uri)).use { it.copyTo(output) } }
                }
                val key = items.map { it.uri.toString() }.sorted().hashCode().toString()
                viewModel.sharedAlbums.contributeFilesToSharedAlbum(albumId, files, key, caption, done)
            })
    }
    (incoming.request as? IncomingRequest.Browser)?.let { request ->
        LaunchedEffect(request) { openInBrowser(context, request.url); closeIncoming() }
    }
    (incoming.request as? IncomingRequest.SharedActivity)?.let { request ->
        LaunchedEffect(request) {
            viewModel.sharedAlbums.requestOpen(request.albumId, request.invitations)
            onIncomingChange(IncomingSession.None)
        }
    }
    (incoming.request as? IncomingRequest.Invitation)?.takeUnless { incomingSuspended }?.let { request ->
        IncomingInvitation(request.link, session.accountState, actions, ::closeIncoming, ::connectForIncoming)
    }

    editingId?.let { id -> gallery.catalogItems.firstOrNull { it.id == id }?.let { media ->
        val canUpload = (session.accountState as? ICloudAccountState.SignedIn)?.status == SessionStatus.VERIFIED
        PhotoEditorScreen(media, viewModel.photoEdits::source, close = { editingId = null }, canUpload = canUpload, saved = { uri, upload ->
            viewModel.sync.refresh()
            if (upload) {
                // The session may have changed while the copy was saving.
                val stillSignedIn = (viewModel.account.state.value.accountState as? ICloudAccountState.SignedIn)?.status == SessionStatus.VERIFIED
                if (stillSignedIn) uploadPicked(context, viewModel, listOf(uri)) else viewModel.editedCopyNotUploaded()
            }
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
    // Each tile reads its own badge through this State, so a badge change recomposes that tile alone.
    val backupBadges = rememberUpdatedState(remember(transfers.backup.enabled, transfers.uploadsByMediaId) {
        backupBadges(transfers.backup.enabled, transfers.uploadsByMediaId)
    })
    CompositionLocalProvider(LocalPreviewStorageLow provides previewStorageLow, LocalBackupBadges provides backupBadges,
        LocalSharedActivity provides sharedActivity, LocalNotificationAccess provides notificationAccess) {
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
    }
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
        is RootDialog.ConfirmPhoneTrash -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(pluralStringResource(R.plurals.phone_trash_not_backed_up_title, current.mediaIds.size, current.mediaIds.size)) },
            text = {
                Text(if (current.notBackedUp == current.mediaIds.size) {
                    pluralStringResource(R.plurals.phone_trash_none_backed_up, current.notBackedUp, current.notBackedUp)
                } else {
                    pluralStringResource(R.plurals.phone_trash_some_not_backed_up, current.mediaIds.size, current.notBackedUp, current.mediaIds.size)
                })
            },
            confirmButton = { TextButton(onClick = { dialog = null; platform.confirmPhoneTrash(current.mediaIds) },
                modifier = Modifier.testTag("confirm-unbacked-phone-trash")) { Text(stringResource(R.string.trash_phone_copy)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
            modifier = Modifier.testTag("unbacked-phone-trash"),
        )
        is RootDialog.ConfirmMixedTrash -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.mixed_trash_title)) },
            text = {
                // The phone items' own warning joins this one, so the user confirms once, knowing what isn't backed up.
                val notBackedUp = notBackedUpCount(gallery.catalogItems.filter { it.id in current.phoneIds }, transfers.uploadsByMediaId)
                Text(listOfNotNull(
                    pluralStringResource(R.plurals.mixed_trash_cloud, current.cloudIds.size, current.cloudIds.size),
                    pluralStringResource(R.plurals.mixed_trash_phone, current.phoneIds.size, current.phoneIds.size),
                    notBackedUp.takeIf { it > 0 }?.let {
                        pluralStringResource(R.plurals.phone_trash_some_not_backed_up, current.phoneIds.size, it, current.phoneIds.size)
                    },
                ).joinToString("\n\n"))
            },
            confirmButton = { TextButton(onClick = {
                dialog = null
                val phoneItems = gallery.catalogItems.filter { it.id in current.phoneIds }
                if (phoneItems.size == current.phoneIds.size) {
                    viewModel.mixedTrashRequested(current.cloudIds)
                    // Already confirmed here, including any items that aren't backed up: no second dialog.
                    platform.confirmPhoneTrash(current.phoneIds)
                } else viewModel.phoneTrashFailed()
            }) { Text(stringResource(R.string.mixed_trash_action)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
        is RootDialog.MediaLocation -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.media_location_title)) },
            text = { Text(stringResource(R.string.media_location_body)) },
            confirmButton = { TextButton(onClick = {
                dialog = null
                pendingUpload = current.upload
                mediaLocationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            }) { Text(stringResource(R.string.welcome_continue)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.not_now)) } },
        )
        RootDialog.DemoUpload, null -> Unit
    }
    transfers.backupSetup?.takeUnless { it.awaitingPermissions }?.let { setup ->
        BackupDisclosure(setup, storageFull = session.accountInfo.storage?.let { !it.isDemo && it.usedBytes >= it.totalBytes } == true,
            accept = viewModel.transfers::acceptBackupDisclosure, dismiss = viewModel.transfers::dismissBackupSetup)
    }
    LaunchedEffect(notificationAnswered) {
        if (!notificationAnswered) return@LaunchedEffect
        notificationAnswered = false
        awaitingNotificationAnswer = false
        platform.askNext(TransferPrompt.NOTIFICATIONS)
    }
    if (transfers.freeUpSpace.leftover > 0) ContinueFreeUpSpace(transfers.freeUpSpace.leftover,
        resume = viewModel.transfers.backup::continueFreeUp, discard = viewModel.transfers.backup::cancelFreeUp)
    // Wherever "Free up space" started, its progress and Cancel show here.
    transfers.freeUpSpace.takeIf { it.checking && !it.awaitingConsent }?.let { run ->
        FreeUpSpaceProgress(run, cancel = viewModel.transfers.backup::cancelFreeUp)
    }
    when (transferPrompt) {
        TransferPrompt.NOTIFICATIONS -> NotificationsRationale(
            allow = { transferPrompt = null; platform.requestNotifications() },
            notNow = { viewModel.transfers.backup.notificationsAsked(); platform.askNext(TransferPrompt.NOTIFICATIONS) },
        )
        TransferPrompt.MOBILE_DATA -> MobileDataPrompt(
            answer = { useMobileData, remember ->
                viewModel.transfers.backup.chooseMobileData(useMobileData, remember)
                platform.askNext(TransferPrompt.MOBILE_DATA)
            },
            dismiss = { transferPrompt = null; pendingTransfer = null },
        )
        null -> Unit
    }
}

/** Hands a web link back to a browser, leaving Alba out of the choice so it cannot loop back here. */
private fun openInBrowser(context: Context, url: String) {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    val alba = albaComponents(context.packageManager, context.packageName, url)
    runCatching {
        context.startActivity(Intent.createChooser(view, null).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, alba)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
private fun BackupDisclosure(setup: BackupSetup, storageFull: Boolean, accept: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.back_up_new_photos)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (setup.demo) stringResource(R.string.demo_backup_disclosure)
                    else stringResource(R.string.backup_disclosure, setup.destinationLabel))
                if (!setup.demo) Text(stringResource(R.string.backup_disclosure_notifications), style = MaterialTheme.typography.bodySmall)
                // Warn before the user commits: with a full iCloud nothing can back up until there is space.
                if (storageFull && !setup.demo) Text(stringResource(R.string.backup_disclosure_storage_full),
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("backup-storage-full-warning"))
            }
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

    /** Starts [pending] after asking, once, about notifications and, on mobile data, whether to use it. */
    fun beforeTransfer(pending: PendingTransfer)

    /** Asks the question after [after], or starts the held transfer when there is none. */
    fun askNext(after: TransferPrompt?)

    /** Asks Android for notifications, then asks the next question whatever the answer. */
    fun requestNotifications()
    fun trashOnPhone(items: List<GalleryMedia>, restore: Boolean)

    /** The user confirmed moving items that are not all backed up to Android's trash. */
    fun confirmPhoneTrash(mediaIds: List<String>)

    /** Asks Android to restore phone items that Alba moved to its trash. */
    fun restoreOnPhone(contentUris: List<String>)
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
    override fun undo(action: UndoAction) = when (action) {
        is UndoAction.CloudTrash -> edits.undoCloudTrash(action.mediaIds)
        is UndoAction.PhoneTrash -> platform.restoreOnPhone(action.contentUris)
        is UndoAction.MixedTrash -> {
            edits.undoCloudTrash(action.cloud.mediaIds)
            platform.restoreOnPhone(action.phone.contentUris)
        }
    }

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
    override fun openBackup() = account.openAccount(ProfilePage.BACKUP)
    override fun startDemo() = account.startDemo()

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
    override fun confirmMixedTrash(items: List<GalleryMedia>) {
        val cloudIds = items.filter { it.origin == MediaOrigin.ICLOUD }.map { it.id }
        val phoneIds = items.filter { it.origin == MediaOrigin.DEVICE }.map { it.id }
        when {
            phoneIds.isEmpty() -> confirmCloudTrash(cloudIds, trashed = true)
            cloudIds.isEmpty() -> platform.trashOnPhone(items, restore = false)
            else -> platform.confirm(RootDialog.ConfirmMixedTrash(cloudIds, phoneIds))
        }
    }
    override fun contributeToSharedAlbum(albumId: String, mediaIds: List<String>, caption: String) = shared.contributeToSharedAlbum(albumId, mediaIds, caption)
    override fun createSharedAlbum(name: String, generation: SharedAlbumGeneration) = shared.createSharedAlbum(name, generation)

    override fun runBatch(action: BatchAction) = when (action) {
        BatchAction.UPLOAD -> platform.beforeTransfer(PendingTransfer.Upload(UploadTarget.Selection))
        BatchAction.KEEP_OFFLINE, BatchAction.SAVE_TO_PHONE -> platform.beforeTransfer(PendingTransfer.Batch(action))
        else -> transfers.runBatch(action)
    }
    override fun retryBatch(batchId: String) = transfers.retryBatch(batchId)
    override fun cancelDownloadBatch(batchId: String) = transfers.cancelDownloadBatch(batchId)
    override fun pickUpload() = platform.beforeTransfer(PendingTransfer.PickUpload)
    override fun requestUpload(mediaId: String) = platform.beforeTransfer(PendingTransfer.Upload(UploadTarget.Media(mediaId)))
    override fun continuePastUnresolved(transferId: TransferId) = transfers.continuePastUnresolved(transferId)
    override fun retryTransfer(transferId: TransferId) = platform.beforeTransfer(PendingTransfer.Retry(transferId.value))
    override fun retryAllTransfers() = platform.beforeTransfer(PendingTransfer.RetryAll)
    override fun cancelUploads() = transfers.backup.cancelUploads()
    override fun freeUpSpace(mediaIds: List<String>) = if (mediaIds.isNotEmpty()) platform.beforeTransfer(PendingTransfer.FreeUp(mediaIds)) else Unit
    override fun share(items: List<GalleryMedia>) = viewModel.exports.share(items)
    override fun cancelShare() = viewModel.exports.cancelShare()

    override fun closeMedia() = gallery.closeMedia()
    override suspend fun loadViewer(mediaId: String) = gallery.loadViewer(mediaId)
    override suspend fun openPlayback(mediaId: String, position: Long, length: Long) = gallery.openPlayback(mediaId, position, length)
    override fun edit(media: GalleryMedia) = platform.edit(media)
    override fun saveToGallery(media: GalleryMedia) = viewModel.exports.saveToGallery(media)
    override fun exportOriginal(media: GalleryMedia) = platform.exportDocument(media, livePair = false)
    override fun exportLivePhoto(media: GalleryMedia) = platform.exportDocument(media, livePair = true)
    override fun keepOriginalOffline(mediaId: String) = platform.beforeTransfer(PendingTransfer.KeepOffline(mediaId))
    override fun removeCachedOriginal(mediaId: String) = transfers.removeCachedOriginal(mediaId)
    override fun cancelOfflineDownload() = transfers.cancelOfflineDownload()
    override fun moveVerifiedPhotoToTrash(mediaId: String) = transfers.requestVerifiedTrash(mediaId)
    override fun useElsewhere(media: GalleryMedia, use: ExternalUse) = viewModel.exports.useElsewhere(media, use)

    override fun closeAccount() { account.closeAccount(); onAccountClosed() }
    override fun signIn(appleId: String, password: String) = account.signIn(appleId, password)
    override fun submitTwoFactor(code: String) = account.submitTwoFactor(code)
    override fun resendTwoFactor() = account.resendTwoFactor()
    override fun sendCodeBySms() = account.sendCodeBySms()
    override fun clearSignInProblem() = account.clearSignInProblem()
    override val signInDraft get() = account.signInDraft
    override fun signOut() = viewModel.signOut()
    override fun checkConnection() = account.checkConnection()
    override fun retryRestore() = viewModel.sync.refresh()
    override fun enableAutomaticBackup() = transfers.beginAutomaticBackup()
    override fun disableAutomaticBackup() = transfers.disableAutomaticBackup()
    override fun demoBackupAction(transferId: TransferId) = transfers.continuePastUnresolved(transferId)
    override fun accountPageShown() = account.accountPageShown()
    override fun setBackupSettings(settings: BackupSettings) = transfers.backup.setBackupSettings(settings)
    override fun loadDeviceFolders() = transfers.backup.loadDeviceFolders()
    override fun refreshBackupStatus() = transfers.backup.refreshBackupStatus()
    override fun loadFreeUpSpace() = transfers.backup.loadFreeUpSpace()
    override fun retryAfterStorageFull() = transfers.backup.retryAfterStorageFull()
    override fun setDiagnosticsEnabled(enabled: Boolean) = viewModel.setDiagnosticsEnabled(enabled)
    override fun checkPhoneStorage() = viewModel.phoneStorage.checkPhoneStorage()
    override fun clearLocalMedia(category: LocalMediaCategory) = viewModel.phoneStorage.clearLocalMedia(category)

    override suspend fun sharedActivity(albumId: String, rank: Int) = shared.sharedActivity(albumId, rank)
    override suspend fun sharedPostDiscussion(postId: String) = shared.sharedPostDiscussion(postId)
    override suspend fun sharedPostPhotos(postId: String) = shared.sharedPostPhotos(postId)
    override suspend fun sharedPostPreviews(postIds: List<String>) = shared.sharedPostPreviews(postIds)
    override fun setSharedActivityNotifications(enabled: Boolean) = shared.setNotifications(enabled)
    override fun sharedNotificationsAsked() = shared.notificationsAsked()
    override fun consumeSharedOpen() = shared.consumeOpen()
    override fun markInvitationsRead() = shared.markInvitationsRead()
    override suspend fun sharedManagement(albumId: String) = shared.sharedManagement(albumId)
    override suspend fun sharedDiscussion(id: String) = shared.sharedDiscussion(id)
    override suspend fun changeSharedAlbum(albumId: String, command: SharedCommand) = shared.changeSharedAlbum(albumId, command)
    override suspend fun pendingSharedInvitations() = shared.pendingSharedInvitations()
    override suspend fun resolveSharedInvitation(url: String) = shared.resolveSharedInvitation(url)
    override suspend fun respondSharedInvitation(invitationId: String, accept: Boolean) = shared.respondSharedInvitation(invitationId, accept)
}
