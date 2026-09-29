package dev.mela.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.IntentSenderRequest
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.mela.engine.model.BackupDraftId
import dev.mela.engine.companion.BatchAction
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mela.app.ui.MelaApp
import dev.mela.app.ui.theme.MelaTheme
import dev.mela.engine.model.BackupDraft
import dev.mela.engine.model.BackupSetupEffect
import dev.mela.engine.model.SystemConsentCallback
import dev.mela.engine.model.TrashAttemptId
import kotlinx.coroutines.launch
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.debounce

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MelaViewModel> { MelaViewModel.Factory(this) }
    private var incoming by mutableStateOf<IncomingMediaRequest?>(null)
    private var invitation by mutableStateOf<String?>(null)
    private var returnToCaller = false

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        invitation = incomingSharedInvitation(intent)
        incoming = if (invitation == null) incomingMedia(intent) else null
        returnToCaller = incoming != null || invitation != null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("incomingHandled", true)
        invitation?.let { outState.putString("incomingInvitation", it) }
        incoming?.let { outState.putStringArrayList("incomingUris", ArrayList(it.uris)); outState.putBoolean("savedCopy", it.savedCopy) }
        outState.putBoolean("returnToCaller", returnToCaller)
        super.onSaveInstanceState(outState)
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        invitation = if (savedInstanceState?.getBoolean("incomingHandled") == true) savedInstanceState.getString("incomingInvitation") else incomingSharedInvitation(intent)
        incoming = if (savedInstanceState?.getBoolean("incomingHandled") == true) savedInstanceState.getStringArrayList("incomingUris")?.let {
            IncomingMediaRequest(it, savedInstanceState.getBoolean("returnToCaller"), savedInstanceState.getBoolean("savedCopy"))
        } else if (invitation == null) incomingMedia(intent) else null
        returnToCaller = savedInstanceState?.getBoolean("returnToCaller") ?: (incoming != null || invitation != null)
        enableEdgeToEdge()
        val onboarding = OnboardingPreferences(this)
        if (BuildConfig.BENCHMARK_MODE) onboarding.complete()
        setContent {
            var showWelcome by rememberSaveable { mutableStateOf(!onboarding.completed) }
            if (showWelcome && incoming == null && invitation == null) {
                MelaTheme(darkTheme = isSystemInDarkTheme()) {
                    dev.mela.app.ui.WelcomeScreen(
                        onConnect = {
                            onboarding.complete()
                            viewModel.openAccount()
                            showWelcome = false
                        },
                        onDemo = { onboarding.complete(); showWelcome = false },
                    )
                }
                return@setContent
            }
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            var incomingSuspended by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(uiState.isAccountOpen) { if (!uiState.isAccountOpen) incomingSuspended = false }
            incoming?.takeUnless { incomingSuspended }?.let { request -> IncomingMediaScreen(request, uiState,
                close = { incoming = null; if (returnToCaller) finish() },
                connect = { incomingSuspended = true; viewModel.openAccount() },
                upload = { uris ->
                    returnToCaller = false
                    uris.forEach { uri -> runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
                    viewModel.uploadSelected(uris)
                }) }
            invitation?.takeUnless { incomingSuspended }?.let { link ->
                MelaTheme(darkTheme = isSystemInDarkTheme()) {
                    val account = uiState.accountState as? dev.mela.protocol.account.ICloudAccountState.SignedIn
                    if (link.isBlank()) androidx.compose.material3.AlertDialog(
                        onDismissRequest = { invitation = null; if (returnToCaller) finish() },
                        title = { androidx.compose.material3.Text(getString(R.string.shared_invitations)) },
                        text = { androidx.compose.material3.Text(getString(R.string.shared_invalid_link)) },
                        confirmButton = { androidx.compose.material3.TextButton(onClick = { invitation = null; if (returnToCaller) finish() }) { androidx.compose.material3.Text(getString(R.string.cancel)) } })
                    else if (account?.status == dev.mela.protocol.account.SessionStatus.VERIFIED) {
                        key(account.appleId, link) {
                            dev.mela.app.ui.SharedInvitations(dev.mela.app.ui.LibraryActions(
                                pendingSharedInvitations = viewModel::pendingSharedInvitations,
                                resolveSharedInvitation = viewModel::resolveSharedInvitation,
                                respondSharedInvitation = viewModel::respondSharedInvitation,
                            ), dismiss = { invitation = null; if (returnToCaller) finish() }, initialUrl = link, invalidLink = link.isBlank())
                        }
                    } else androidx.compose.material3.AlertDialog(onDismissRequest = { invitation = null; if (returnToCaller) finish() },
                        title = { androidx.compose.material3.Text(getString(R.string.shared_invitations)) },
                        text = { androidx.compose.material3.Text(getString(R.string.shared_invitation_sign_in)) },
                        confirmButton = { androidx.compose.material3.TextButton(onClick = { incomingSuspended = true; viewModel.openAccount() }) { androidx.compose.material3.Text(getString(R.string.incoming_connect)) } },
                        dismissButton = { androidx.compose.material3.TextButton(onClick = { invitation = null; if (returnToCaller) finish() }) { androidx.compose.material3.Text(getString(R.string.cancel)) } })
                }
            }
            var editingId by rememberSaveable { mutableStateOf<String?>(null) }
            var pendingCloudIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
            var pendingCloudTrashed by rememberSaveable { mutableStateOf(true) }
            var pendingRemovalAlbum by rememberSaveable { mutableStateOf<String?>(null) }
            val edits = remember { PhotoEditStore(this, (application as MelaApplication).graph.galleryRepository) }
            val phoneTrash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    viewModel.clearSelection(); viewModel.closeDetail(); viewModel.refresh()
                }
            }
            fun requestPhoneTrash(items: List<dev.mela.engine.model.GalleryMedia>, restore: Boolean) {
                if (Build.VERSION.SDK_INT < 30 || items.isEmpty()) return
                val uris = items.mapNotNull { item ->
                    (if (item.origin == dev.mela.engine.model.MediaOrigin.DEVICE) item.originalReference else item.linkedDeviceReference)?.let(android.net.Uri::parse)
                }.distinct()
                if (uris.size != items.size || uris.size > 2000) { viewModel.localOperationFailed(); return }
                runCatching { phoneTrash.launch(IntentSenderRequest.Builder(android.provider.MediaStore.createTrashRequest(contentResolver, uris, !restore).intentSender).build()) }
                    .onFailure { viewModel.localOperationFailed() }
            }
            editingId?.let { id -> uiState.catalogItems.firstOrNull { it.id == id }?.let { media ->
                dev.mela.app.ui.PhotoEditorScreen(media, edits::source, close = { editingId = null }, saved = { uri ->
                    viewModel.refresh(); returnToCaller = false
                    incoming = IncomingMediaRequest(listOf(uri.toString()), external = false, savedCopy = true)
                })
            } }

            val shareIntent by viewModel.shareIntent.collectAsStateWithLifecycle()
            LaunchedEffect(shareIntent) {
                shareIntent?.let { prepared ->
                    viewModel.consumeShare()
                    runCatching { startActivity(android.content.Intent.createChooser(prepared, getString(R.string.share))) }
                        .onFailure { viewModel.shareFailed() }
                }
            }
            var hasDevicePhotoAccess by remember { mutableStateOf(hasDevicePhotoAccess()) }
            LaunchedEffect(hasDevicePhotoAccess) {
                if (hasDevicePhotoAccess) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    deviceLibraryChanges(contentResolver).debounce(350).collect { viewModel.deviceLibraryChanged() }
                }
            }
            var pendingBackupId by rememberSaveable { mutableStateOf<String?>(null) }
            var pendingBackupDestination by rememberSaveable { mutableStateOf("") }
            var backupPermissionPending by rememberSaveable { mutableStateOf(false) }
            var pendingUpload by rememberSaveable { mutableStateOf<String?>(null) }
            var pendingTrashAttemptId by rememberSaveable { mutableStateOf<String?>(null) }
            var exportId by rememberSaveable { mutableStateOf<String?>(null) }
            var exportingLive by rememberSaveable { mutableStateOf(false) }
            val documentExporter = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val id = exportId
                val uri = result.data?.data
                exportId = null
                if (result.resultCode == Activity.RESULT_OK && id != null && uri != null) viewModel.exportDocument(id, uri, exportingLive)
            }
            fun chooseExport(media: dev.mela.engine.model.GalleryMedia, livePair: Boolean) {
                exportId = media.id
                exportingLive = livePair
                documentExporter.launch(android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    type = if (livePair) "application/zip" else media.mimeType
                    putExtra(android.content.Intent.EXTRA_TITLE, if (livePair) exportName(media).substringBeforeLast('.') + "-LivePhoto.zip" else exportName(media))
                })
            }
            val scope = rememberCoroutineScope()
            val uploadPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.PickMultipleVisualMedia(50),
            ) { uris ->
                uris.forEach { uri -> runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
                viewModel.uploadSelected(uris)
            }
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                hasDevicePhotoAccess = hasDevicePhotoAccess()
            }
            val backupPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                pendingBackupId?.let { viewModel.finishAutomaticBackup(BackupDraftId(it)) }
                pendingBackupId = null
                backupPermissionPending = false
                hasDevicePhotoAccess = hasDevicePhotoAccess()
            }
            val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                val upload = pendingUpload
                pendingUpload = null
                if (granted) {
                    if (upload == "batch") viewModel.runBatch(BatchAction.UPLOAD)
                    else upload?.let(viewModel::requestUpload)
                }
            }
            fun requestGalleryUpload(id: String) {
                if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    pendingUpload = id
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                } else if (id == "batch") viewModel.runBatch(BatchAction.UPLOAD) else viewModel.requestUpload(id)
            }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                hasDevicePhotoAccess = hasDevicePhotoAccess()
                viewModel.setDeviceMediaAccess(hasDevicePhotoAccess, hasLimitedPhotoAccess())
                viewModel.refresh()
            }
            val trashLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartIntentSenderForResult(),
            ) { activityResult ->
                val attemptId = pendingTrashAttemptId ?: return@rememberLauncherForActivityResult
                pendingTrashAttemptId = null
                val callback = when (activityResult.resultCode) {
                    Activity.RESULT_OK -> SystemConsentCallback.APPROVED
                    Activity.RESULT_CANCELED -> SystemConsentCallback.CANCELED
                    else -> SystemConsentCallback.DENIED
                }
                viewModel.recordTrashResult(TrashAttemptId(attemptId), callback)
            }

            LaunchedEffect(hasDevicePhotoAccess) {
                viewModel.setDeviceMediaAccess(hasDevicePhotoAccess, hasLimitedPhotoAccess())
            }

            val darkTheme = isSystemInDarkTheme()
            androidx.compose.runtime.SideEffect {
                val lightBars = !darkTheme && (uiState.selectedMedia == null || uiState.isAccountOpen)
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
            MelaTheme(darkTheme = darkTheme) {
                MelaApp(
                    libraryActions = dev.mela.app.ui.LibraryActions(
                        pendingSharedInvitations = viewModel::pendingSharedInvitations, resolveSharedInvitation = viewModel::resolveSharedInvitation, respondSharedInvitation = viewModel::respondSharedInvitation,
                        sharedActivity = viewModel::sharedActivity, sharedPostDiscussion = viewModel::sharedPostDiscussion, sharedPostPhotos = viewModel::sharedPostPhotos,
                        sharedManagement = viewModel::sharedManagement, sharedDiscussion = viewModel::sharedDiscussion, changeSharedAlbum = viewModel::changeSharedAlbum,
                        prefetchPreviews = viewModel::prefetchPreviews,
                        trash = ::requestPhoneTrash, edit = { editingId = it.id },
                        loadViewer = viewModel::loadViewer, selectItems = viewModel::selectItems,
                        favorite = viewModel::setFavorite, createAlbum = viewModel::createAlbum, createSharedAlbum = viewModel::createSharedAlbum, contributeToSharedAlbum = viewModel::contributeToSharedAlbum,
                        createAlbumWithPhotos = viewModel::createAlbumWithPhotos,
                        favorites = viewModel::setFavorites,
                        cloudTrash = { ids, trashed -> pendingRemovalAlbum = null; pendingCloudIds = ids.toList(); pendingCloudTrashed = trashed },
                        removeFromAlbum = { album, ids -> pendingRemovalAlbum = album; pendingCloudIds = ids.toList() },
                        deleteAlbum = viewModel::deleteAlbum, renameAlbum = viewModel::renameAlbum, addToAlbum = viewModel::addToAlbum,
                        share = viewModel::share, cancelShare = viewModel::cancelShare, saveToGallery = viewModel::saveToGallery,
                        exportOriginal = { chooseExport(it, false) }, exportLivePhoto = { chooseExport(it, true) },
                        checkConnection = viewModel::checkConnection,
                        cancelDownloadBatch = viewModel::cancelDownloadBatch, cancelOfflineDownload = viewModel::cancelOfflineDownload,
                        checkPhoneStorage = viewModel::checkPhoneStorage, clearLocalMedia = viewModel::clearLocalMedia,
                    ),
                    onQueryChange = viewModel::setQuery,
                    onToggleSelection = viewModel::toggleSelection,
                    onSelectAll = viewModel::selectAllShown,
                    onClearSelection = viewModel::clearSelection,
                    onBatch = { if (it == BatchAction.UPLOAD) requestGalleryUpload("batch") else viewModel.runBatch(it) },
                    onRetryBatch = viewModel::retryBatch,
                    playback = viewModel::openPlayback,
                    state = uiState,
                    onRefresh = viewModel::refresh,
                    onSelectFilter = viewModel::selectFilter,
                    onSelectMedia = viewModel::selectMedia,
                    onCloseDetail = viewModel::closeDetail,
                    onRequestPreview = viewModel::requestPreview,
                    onRequestDevicePhotos = {
                        permissionLauncher.launch(requestedDevicePhotoPermissions().toTypedArray())
                    },
                    onPickUpload = {
                        uploadPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                        )
                    },
                    onKeepOriginalOffline = viewModel::keepOriginalOffline,
                    onRemoveCachedOriginal = viewModel::removeCachedOriginal,
                    onRequestUpload = ::requestGalleryUpload,
                    onContinuePastUnresolved = viewModel::continuePastUnresolved,
                    onMoveVerifiedPhotoToTrash = { mediaId ->
                        viewModel.requestVerifiedTrash(mediaId) { handoff ->
                            pendingTrashAttemptId = handoff.attemptId.value
                            trashLauncher.launch(
                                IntentSenderRequest.Builder(handoff.intentSender).build(),
                            )
                        }
                    },
                    onDismissMessage = viewModel::dismissMessage,
                    onOpenAccount = viewModel::openAccount,
                    onCloseAccount = viewModel::closeAccount,
                    onSignIn = viewModel::signIn,
                    onSubmitTwoFactor = viewModel::submitTwoFactor,
                    onResendTwoFactor = viewModel::resendTwoFactor,
                    onSignOut = viewModel::signOut,
                    onEnableAutomaticBackup = {
                        viewModel.beginAutomaticBackup { draft -> pendingBackupId = draft.id.value; pendingBackupDestination = draft.destinationLabel }
                    },
                    onDisableAutomaticBackup = viewModel::disableAutomaticBackup,
                )

                if (pendingCloudIds.isNotEmpty()) {
                    val removeAlbum = pendingRemovalAlbum
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { pendingCloudIds = emptyList() },
                        title = { androidx.compose.material3.Text(stringResource(if (removeAlbum != null) R.string.remove_from_album else if (pendingCloudTrashed) R.string.trash_in_icloud else R.string.restore_from_icloud)) },
                        text = { androidx.compose.material3.Text(androidx.compose.ui.res.pluralStringResource(if (removeAlbum != null) R.plurals.remove_album_confirmation else if (pendingCloudTrashed) R.plurals.cloud_trash_confirmation else R.plurals.cloud_restore_confirmation, pendingCloudIds.size, pendingCloudIds.size)) },
                        confirmButton = { androidx.compose.material3.TextButton(onClick = {
                            if (removeAlbum != null) viewModel.removeFromAlbum(removeAlbum, pendingCloudIds)
                            else viewModel.setCloudTrashed(pendingCloudIds, pendingCloudTrashed)
                            pendingCloudIds = emptyList()
                        }) { androidx.compose.material3.Text(stringResource(if (removeAlbum != null) R.string.remove_from_album else if (pendingCloudTrashed) R.string.trash_in_icloud else R.string.restore_from_icloud)) } },
                        dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingCloudIds = emptyList() }) { androidx.compose.material3.Text(stringResource(R.string.cancel)) } },
                    )
                }
                pendingBackupId?.takeUnless { backupPermissionPending }?.let { draftId ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { pendingBackupId = null },
                        title = { androidx.compose.material3.Text(stringResource(R.string.back_up_new_photos)) },
                        text = { androidx.compose.material3.Text(stringResource(R.string.backup_disclosure, pendingBackupDestination)) },
                        confirmButton = {
                            androidx.compose.material3.Button(
                                onClick = {
                                    scope.launch {
                                        when (val effect = viewModel.acceptAutomaticBackup(BackupDraftId(draftId))) {
                                            is BackupSetupEffect.RequestMediaPermissions -> {
                                                backupPermissionPending = true
                                                backupPermissionLauncher.launch(effect.permissions.toTypedArray())
                                            }
                                            is BackupSetupEffect.Refused -> {
                                                pendingBackupId = null
                                            }
                                        }
                                    }
                                },
                            ) { androidx.compose.material3.Text(stringResource(R.string.welcome_continue)) }
                        },
                        dismissButton = {
                            androidx.compose.material3.OutlinedButton(
                                onClick = { pendingBackupId = null },
                            ) { androidx.compose.material3.Text(stringResource(R.string.not_now)) }
                        },
                    )
                }
            }
        }
    }

    private fun hasLimitedPhotoAccess(): Boolean = Build.VERSION.SDK_INT >= 34 &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED

    private fun hasDevicePhotoAccess(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else {
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else listOf(devicePhotoPermission())
        }
        return permissions.any { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestedDevicePhotoPermissions(): List<String> = buildList {
        add(devicePhotoPermission())
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.READ_MEDIA_VIDEO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
    }

    private fun devicePhotoPermission(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
}
