package dev.mela.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import dev.mela.app.R
import dev.mela.engine.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun sharedRole(role: SharedAlbumRole): String = stringResource(when (role) {
    SharedAlbumRole.OWNER -> R.string.shared_person_owner
    SharedAlbumRole.MANAGER -> R.string.shared_role_manager
    SharedAlbumRole.CONTRIBUTOR -> R.string.shared_person_contributor
    SharedAlbumRole.COMMENTER -> R.string.shared_role_commenter
    SharedAlbumRole.VIEWER -> R.string.shared_person_viewer
})

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedAlbumControls(album: GalleryCollection, actions: LibraryActions, dismiss: () -> Unit) {
    var data by remember(album.id) { mutableStateOf<SharedAlbumManagement?>(null) }
    var error by remember(album.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var name by rememberSaveable(album.id) { mutableStateOf(album.name) }
    var email by rememberSaveable(album.id) { mutableStateOf("") }
    var pending by remember { mutableStateOf<SharedCommand?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(album.id, revision) {
        error = false
        try { data = actions.sharedManagement(album.id) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = true }
    }
    MelaBottomSheet(onDismissRequest = { if (!busy) dismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("shared-management")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MelaSheetHeading(stringResource(R.string.shared_manage), album.name, dismiss, !busy)
            if (busy || (data == null && !error)) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error) {
                Text(stringResource(R.string.shared_action_failed), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { revision++ }, enabled = !busy) { Text(stringResource(R.string.shared_reload)) }
            }
            data?.let { state ->
                val info = requireNotNull(state.album.shared)
                Text(sharedRole(info.role))
                if (info.canManage) {
                    MelaGroup {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(name, { name = it.take(255) }, label = { Text(stringResource(R.string.album_name)) }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                            TextButton(onClick = { pending = SharedCommand(SharedAction.RENAME, value = name.trim()) }, enabled = !busy && name.isNotBlank() && name != state.album.name) { Text(stringResource(R.string.rename_album)) }
                            OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.shared_invite_email)) }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("shared-invite-email"))
                            OutlinedButton(onClick = { pending = SharedCommand(SharedAction.INVITE, value = email.trim()) }, enabled = !busy && email.contains('@')) { Text(stringResource(R.string.shared_invite)) }
                            SharedToggle(stringResource(R.string.shared_public), state.publicAccess, !busy) { pending = SharedCommand(SharedAction.PUBLIC_ACCESS, enabled = it) }
                            if (info.generation == SharedAlbumGeneration.LEGACY) {
                                SharedToggle(stringResource(R.string.shared_allow_posts), state.allowContributions, !busy) { pending = SharedCommand(SharedAction.CONTRIBUTIONS, enabled = it) }
                            } else {
                                SharedToggle(stringResource(R.string.shared_access_requests), state.allowAccessRequests, !busy) { pending = SharedCommand(SharedAction.ACCESS_REQUESTS, enabled = it) }
                                SharedToggle(stringResource(R.string.shared_temporary), state.temporary, !busy) { pending = SharedCommand(SharedAction.TEMPORARY, enabled = it) }
                            }
                        }
                    }
                }
                if (info.canManage && info.generation == SharedAlbumGeneration.MODERN && !state.publicAccess) {
                    OutlinedButton(onClick = { pending = SharedCommand(SharedAction.CREATE_INVITE_LINK) }, enabled = !busy) { Text(stringResource(R.string.shared_create_link)) }
                    state.invitationLinks.forEachIndexed { index, link ->
                        TextButton(onClick = { clipboard.setText(AnnotatedString(link)) }, enabled = !busy) { Text(stringResource(R.string.shared_copy_invite_link, index + 1)) }
                    }
                    if (state.invitationLinks.isNotEmpty()) TextButton(onClick = { pending = SharedCommand(SharedAction.REVOKE_INVITE_LINKS) }, enabled = !busy) { Text(stringResource(R.string.shared_revoke_links)) }
                }
                state.publicUrl?.let { url ->
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(url)) }, enabled = !busy) { Text(stringResource(R.string.shared_copy_link)) }
                }
                HorizontalDivider()
                Text(stringResource(R.string.shared_people), style = MaterialTheme.typography.titleMedium)
                if (state.participants.isEmpty()) Text(stringResource(R.string.shared_no_people))
                if (info.canManage) state.requests.forEach { request ->
                    Text(request.name)
                    Row {
                        TextButton(onClick = { pending = SharedCommand(SharedAction.APPROVE_REQUEST, request.id) }, enabled = !busy) { Text(stringResource(R.string.shared_approve)) }
                        TextButton(onClick = { pending = SharedCommand(SharedAction.DENY_REQUEST, request.id) }, enabled = !busy) { Text(stringResource(R.string.shared_deny)) }
                    }
                }
                if (info.canManage && state.blocked.isNotEmpty()) {
                    Text(stringResource(R.string.shared_blocked), style = MaterialTheme.typography.titleMedium)
                    state.blocked.forEach { person ->
                        Text(person.name)
                        TextButton(onClick = { pending = SharedCommand(SharedAction.UNBLOCK, person.id) }, enabled = !busy) { Text(stringResource(R.string.shared_unblock)) }
                    }
                }
                state.participants.forEach { p ->
                    MelaGroup {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall)
                            Text(sharedRole(p.role) + if (p.pending) " · " + stringResource(R.string.shared_invited) else "", style = MaterialTheme.typography.bodySmall)
                            if (info.canManage && p.role != SharedAlbumRole.OWNER && !p.isCurrentUser) {
                                if (info.generation == SharedAlbumGeneration.MODERN) {
                                    listOf("contributor" to SharedAlbumRole.CONTRIBUTOR, "commenter" to SharedAlbumRole.COMMENTER, "manager" to SharedAlbumRole.MANAGER).forEach { (wire, role) ->
                                        TextButton(onClick = { pending = SharedCommand(SharedAction.ROLE, p.id, wire) }, enabled = !busy && p.role != role) { Text(sharedRole(role)) }
                                    }
                                }
                                TextButton(onClick = { pending = SharedCommand(SharedAction.REMOVE_PARTICIPANT, p.id) }, enabled = !busy) { Text(stringResource(R.string.shared_remove_person)) }
                            }
                        }
                    }
                }
                if (info.role == SharedAlbumRole.OWNER) {
                    OutlinedButton(onClick = { pending = SharedCommand(SharedAction.DELETE_ALBUM) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.shared_delete_album), color = MaterialTheme.colorScheme.error) }
                } else {
                    OutlinedButton(onClick = { pending = SharedCommand(SharedAction.LEAVE) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.shared_leave)) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    pending?.let { command -> SharedChangeConfirmation(command,
        target = data?.participants?.firstOrNull { it.id == command.subject }?.name
            ?: data?.requests?.firstOrNull { it.id == command.subject }?.name
            ?: data?.blocked?.firstOrNull { it.id == command.subject }?.name,
        cancel = { pending = null }) {
        focus.clearFocus(); pending = null; busy = true; error = false
        scope.launch {
            try {
                actions.changeSharedAlbum(album.id, command)
                if (command.action in setOf(SharedAction.DELETE_ALBUM, SharedAction.LEAVE)) dismiss()
                else { email = ""; revision++ }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = true }
            finally { busy = false }
        }
    } }
}

@Composable
private fun SharedToggle(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = change), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, null, enabled = enabled)
    }
}

@Composable
internal fun SharedPhotoControls(media: GalleryMedia, actions: LibraryActions, dismiss: () -> Unit) =
    SharedDiscussionControls(media.id, media.fileName, false, actions, dismiss)

@Composable
internal fun SharedAssetDiscussion(id: String, title: String, actions: LibraryActions, dismiss: () -> Unit) =
    SharedDiscussionControls(id, title, false, actions, dismiss)

@Composable
internal fun SharedPostControls(post: SharedPost, actions: LibraryActions, dismiss: () -> Unit) =
    SharedDiscussionControls(post.id, post.caption.ifBlank { stringResource(R.string.shared_post_items) }, true, actions, dismiss)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharedDiscussionControls(id: String, title: String, post: Boolean, actions: LibraryActions, dismiss: () -> Unit) {
    val album = id.removePrefix("post:").substringBeforeLast(':')
    var discussion by remember(id) { mutableStateOf<SharedDiscussion?>(null) }
    var management by remember(id) { mutableStateOf<SharedAlbumManagement?>(null) }
    var text by rememberSaveable(id) { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<SharedCommand?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    fun perform(command: SharedCommand) {
        if (busy) return
        focus.clearFocus(); busy = true; error = false
        scope.launch {
            try {
                actions.changeSharedAlbum(album, command)
                if (command.action == SharedAction.REMOVE_MEDIA) dismiss()
                else { if (command.action in setOf(SharedAction.COMMENT, SharedAction.POST_COMMENT)) text = ""; revision++ }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = true }
            finally { busy = false }
        }
    }
    LaunchedEffect(id, revision) {
        error = false
        try { discussion = if (post) actions.sharedPostDiscussion(id) else actions.sharedDiscussion(id); management = actions.sharedManagement(album) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = true }
    }
    MelaBottomSheet(onDismissRequest = { if (!busy) dismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("shared-discussion")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MelaSheetHeading(stringResource(R.string.shared_discussion), title, dismiss, !busy)
            if (busy || (discussion == null && !error)) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error) {
                Text(stringResource(R.string.shared_action_failed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { revision++ }, enabled = !busy) { Text(stringResource(R.string.shared_reload)) }
            }
            discussion?.let { thread ->
                if (thread.canComment) {
                    val emojis = if (thread.generation == SharedAlbumGeneration.LEGACY) listOf("👍") else listOf("👍", "❤️", "😍", "🎉", "🔥", "😂")
                    FlowRow(Modifier.fillMaxWidth()) {
                        emojis.forEach { emoji ->
                            val selected = thread.comments.any { it.reaction && it.isMine && it.text == emoji }
                            FilterChip(selected, { perform(SharedCommand(if (post) SharedAction.POST_REACTION else SharedAction.REACTION, id, if (selected) "" else emoji)) }, label = { Text(emoji) }, enabled = !busy)
                        }
                    }
                    OutlinedTextField(text, { text = it.take(1000) }, label = { Text(stringResource(R.string.shared_add_comment)) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("shared-comment-input"))
                    Button(onClick = { perform(SharedCommand(if (post) SharedAction.POST_COMMENT else SharedAction.COMMENT, id, text.trim())) }, enabled = !busy && text.isNotBlank(), modifier = Modifier.testTag("shared-post-comment")) { Text(stringResource(R.string.shared_post)) }
                }
                if (thread.comments.isEmpty()) Text(stringResource(R.string.shared_no_comments))
                thread.comments.forEach { comment ->
                    Surface(shape = MaterialTheme.shapes.medium, color = melaGroupColor()) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(if (comment.isMine) stringResource(R.string.shared_you) else comment.author, style = MaterialTheme.typography.labelLarge)
                            if (comment.timestamp > 0) Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(comment.timestamp)), style = MaterialTheme.typography.labelSmall)
                            Text(comment.text)
                            if (comment.canDelete) TextButton(onClick = { pending = SharedCommand(if (post) SharedAction.POST_DELETE_COMMENT else SharedAction.DELETE_COMMENT, id, comment.id) }, enabled = !busy) { Text(stringResource(R.string.shared_delete_comment)) }
                        }
                    }
                }
                if (!post) {
                HorizontalDivider()
                OutlinedButton(onClick = { pending = SharedCommand(SharedAction.SAVE_TO_LIBRARY, id) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.shared_save_library)) }
                if (management?.album?.shared?.canManage == true) {
                    if (thread.generation == SharedAlbumGeneration.MODERN) TextButton(onClick = { pending = SharedCommand(SharedAction.COVER, id) }, enabled = !busy) { Text(stringResource(R.string.shared_cover)) }
                }
                if (thread.canRemovePhoto) TextButton(onClick = { pending = SharedCommand(SharedAction.REMOVE_MEDIA, id) }, enabled = !busy) { Text(stringResource(R.string.shared_remove_photo), color = MaterialTheme.colorScheme.error) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    pending?.let { command -> SharedChangeConfirmation(command, cancel = { pending = null }) { pending = null; perform(command) } }
}

@Composable
private fun SharedChangeConfirmation(command: SharedCommand, target: String? = null, cancel: () -> Unit, confirm: () -> Unit) {
    val message = when (command.action) {
        SharedAction.CREATE_INVITE_LINK -> R.string.shared_confirm_invite_link
        SharedAction.REVOKE_INVITE_LINKS -> R.string.shared_confirm_revoke_links
        SharedAction.DELETE_ALBUM -> R.string.shared_confirm_delete
        SharedAction.LEAVE -> R.string.shared_confirm_leave
        SharedAction.REMOVE_MEDIA -> R.string.shared_confirm_remove
        SharedAction.DENY_REQUEST -> R.string.shared_confirm_deny
        SharedAction.UNBLOCK -> R.string.shared_confirm_unblock
        SharedAction.APPROVE_REQUEST, SharedAction.INVITE, SharedAction.ROLE, SharedAction.PUBLIC_ACCESS, SharedAction.ACCESS_REQUESTS, SharedAction.CONTRIBUTIONS -> R.string.shared_confirm_access
        SharedAction.TEMPORARY -> if (command.enabled) R.string.shared_confirm_expire else R.string.shared_confirm_change
        SharedAction.SAVE_TO_LIBRARY -> R.string.shared_confirm_save
        else -> R.string.shared_confirm_change
    }
    AlertDialog(onDismissRequest = cancel, title = { Text(stringResource(R.string.shared_confirm_title)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(message))
            val actionLabel = when (command.action) {
                SharedAction.INVITE -> R.string.shared_invite
                SharedAction.APPROVE_REQUEST -> R.string.shared_approve
                SharedAction.DENY_REQUEST -> R.string.shared_deny
                SharedAction.REMOVE_PARTICIPANT -> R.string.shared_remove_person
                SharedAction.PUBLIC_ACCESS -> R.string.shared_public
                SharedAction.ACCESS_REQUESTS -> R.string.shared_access_requests
                SharedAction.CONTRIBUTIONS -> R.string.shared_allow_posts
                SharedAction.TEMPORARY -> R.string.shared_temporary
                SharedAction.DELETE_COMMENT -> R.string.shared_delete_comment
                SharedAction.COVER -> R.string.shared_cover
                else -> null
            }
            actionLabel?.let { Text(stringResource(it), style = MaterialTheme.typography.titleMedium) }
            target?.let { Text(it) }
            if (command.action == SharedAction.ROLE) Text(sharedRole(when(command.value) {
                "manager" -> SharedAlbumRole.MANAGER
                "commenter" -> SharedAlbumRole.COMMENTER
                else -> SharedAlbumRole.CONTRIBUTOR
            }))
            if (command.action in setOf(SharedAction.PUBLIC_ACCESS, SharedAction.ACCESS_REQUESTS, SharedAction.CONTRIBUTIONS, SharedAction.TEMPORARY)) {
                Text(stringResource(if(command.enabled) R.string.shared_turn_on else R.string.shared_turn_off))
            }
            if (command.action == SharedAction.PUBLIC_ACCESS && command.enabled) Text(stringResource(R.string.shared_public_warning))
            if (command.value.isNotBlank() && command.action in setOf(SharedAction.INVITE, SharedAction.RENAME)) Text(command.value)
        }
    }, confirmButton = { TextButton(onClick = confirm) { Text(stringResource(R.string.shared_confirm)) } },
        dismissButton = { TextButton(onClick = cancel) { Text(stringResource(R.string.shared_cancel)) } })
}
