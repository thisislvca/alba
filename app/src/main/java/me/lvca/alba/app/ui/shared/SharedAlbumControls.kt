package me.lvca.alba.app.ui.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import me.lvca.alba.engine.model.*
import kotlinx.coroutines.launch
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.SharedActions
import me.lvca.alba.app.ui.designsystem.AlbaBottomSheet
import me.lvca.alba.app.ui.designsystem.AlbaCard
import me.lvca.alba.app.ui.designsystem.AlbaLoadState
import me.lvca.alba.app.ui.designsystem.AlbaSheetBody
import me.lvca.alba.app.ui.designsystem.attempt
import me.lvca.alba.app.ui.designsystem.rememberLoadable
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing
import me.lvca.alba.app.ui.timestampLabel
import me.lvca.alba.protocol.fixture.isDemoSharedId

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
internal fun SharedAlbumControls(album: GalleryCollection, actions: SharedActions, dismiss: () -> Unit) {
    val management = rememberLoadable<SharedAlbumManagement?>(album.id, initial = null) { actions.sharedManagement(album.id) }
    val data = management.value
    var busy by remember { mutableStateOf(false) }
    var name by rememberSaveable(album.id) { mutableStateOf(album.name) }
    var email by rememberSaveable(album.id) { mutableStateOf("") }
    var pending by remember { mutableStateOf<SharedCommand?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current
    AlbaBottomSheet(onDismissRequest = { if (!busy) dismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("shared-management")) {
        AlbaSheetBody(stringResource(R.string.shared_manage), dismiss, subtitle = album.name, dismissEnabled = !busy) {
            if (isDemoSharedId(album.id)) Text(stringResource(R.string.demo_shared_notice))
            AlbaLoadState(busy || management.loading, management.failed, stringResource(R.string.shared_action_failed),
                onReload = management::reload, reloadEnabled = !busy)
            data?.let { state ->
                val info = requireNotNull(state.album.shared)
                Text(sharedRole(info.role))
                if (info.canManage) {
                    AlbaCard {
                        Column(Modifier.padding(AlbaSpacing.lg), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    AlbaCard {
                        Column(Modifier.fillMaxWidth().padding(AlbaSpacing.lg)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall)
                            Text(if (p.pending) stringResource(R.string.label_with_detail, sharedRole(p.role), stringResource(R.string.shared_invited)) else sharedRole(p.role),
                                style = MaterialTheme.typography.bodySmall)
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
        }
    }
    pending?.let { command -> SharedChangeConfirmation(command,
        target = data?.participants?.firstOrNull { it.id == command.subject }?.name
            ?: data?.requests?.firstOrNull { it.id == command.subject }?.name
            ?: data?.blocked?.firstOrNull { it.id == command.subject }?.name,
        cancel = { pending = null }) {
        focus.clearFocus(); pending = null; busy = true; management.failed = false
        scope.launch {
            try {
                management.failed = !attempt {
                    actions.changeSharedAlbum(album.id, command)
                    if (command.action in setOf(SharedAction.DELETE_ALBUM, SharedAction.LEAVE)) dismiss()
                    else { email = ""; management.reload() }
                }
            } finally { busy = false }
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
internal fun SharedPhotoControls(media: GalleryMedia, actions: SharedActions, dismiss: () -> Unit) =
    SharedDiscussionControls(media.id, media.fileName, false, actions, dismiss)

@Composable
internal fun SharedAssetDiscussion(id: String, title: String, actions: SharedActions, dismiss: () -> Unit) =
    SharedDiscussionControls(id, title, false, actions, dismiss)

@Composable
internal fun SharedPostControls(post: SharedPost, actions: SharedActions, dismiss: () -> Unit) =
    SharedDiscussionControls(post.id, post.caption.ifBlank { stringResource(R.string.shared_post_items) }, true, actions, dismiss)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharedDiscussionControls(id: String, title: String, post: Boolean, actions: SharedActions, dismiss: () -> Unit) {
    val album = id.removePrefix("post:").substringBeforeLast(':')
    var management by remember(id) { mutableStateOf<SharedAlbumManagement?>(null) }
    val thread = rememberLoadable<SharedDiscussion?>(id, initial = null) {
        (if (post) actions.sharedPostDiscussion(id) else actions.sharedDiscussion(id)).also { management = actions.sharedManagement(album) }
    }
    val discussion = thread.value
    var text by rememberSaveable(id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<SharedCommand?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    fun perform(command: SharedCommand) {
        if (busy) return
        focus.clearFocus(); busy = true; thread.failed = false
        scope.launch {
            try {
                thread.failed = !attempt {
                    actions.changeSharedAlbum(album, command)
                    if (command.action == SharedAction.REMOVE_MEDIA) dismiss()
                    else { if (command.action in setOf(SharedAction.COMMENT, SharedAction.POST_COMMENT)) text = ""; thread.reload() }
                }
            } finally { busy = false }
        }
    }
    AlbaBottomSheet(onDismissRequest = { if (!busy) dismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("shared-discussion")) {
        AlbaSheetBody(stringResource(R.string.shared_discussion), dismiss, subtitle = title, dismissEnabled = !busy) {
            AlbaLoadState(busy || thread.loading, thread.failed, stringResource(R.string.shared_action_failed),
                onReload = thread::reload, reloadEnabled = !busy)
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
                    AlbaCard(contentPadding = PaddingValues(14.dp)) {
                        Column {
                            Text(if (comment.isMine) stringResource(R.string.shared_you) else comment.author, style = MaterialTheme.typography.labelLarge)
                            if (comment.timestamp > 0) Text(timestampLabel(comment.timestamp), style = MaterialTheme.typography.labelSmall)
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
        Column(verticalArrangement = Arrangement.spacedBy(AlbaSpacing.md)) {
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
