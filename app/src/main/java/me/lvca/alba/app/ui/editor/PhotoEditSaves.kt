package me.lvca.alba.app.ui.editor

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.lvca.alba.app.platform.SmallerPhotoCopyRequired
import me.lvca.alba.app.platform.renderPhotoEdit
import me.lvca.alba.app.platform.saveEditedCopy
import me.lvca.alba.engine.model.GalleryMedia
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Where one save stands. [id] ties it to the editor session that started it, which keeps the id across recreation. */
internal sealed interface EditSave {
    val id: Long

    data class Running(override val id: Long, val progress: Float = 0f) : EditSave
    data class Saved(override val id: Long, val uri: Uri, val upload: Boolean) : EditSave
    data class NeedsSmallerCopy(override val id: Long, val width: Int, val height: Int, val upload: Boolean) : EditSave
    data class Failed(override val id: Long) : EditSave
}

/** One full-resolution save: the source photo, the edit and whether the copy goes on to iCloud. */
internal class EditSaveRequest(val media: GalleryMedia, val source: File, val step: EditStep, val allowSmaller: Boolean, val upload: Boolean)

/**
 * Runs editor saves outside the screen, so rotating or recreating the activity neither cancels a save nor deletes the
 * photo it is reading. Saves run one at a time: a full-resolution decode can take most of the memory budget, and a
 * second one must not start until the first has finished and released it.
 */
internal class PhotoEditSaves(
    private val scope: CoroutineScope,
    private val render: suspend (EditSaveRequest, (Float) -> Unit) -> Uri,
) {
    private val saves = MutableStateFlow<Map<Long, EditSave>>(emptyMap())
    val state: StateFlow<Map<Long, EditSave>> = saves.asStateFlow()
    private val oneAtATime = Mutex()
    private val ids = AtomicLong()
    private val inUse = mutableSetOf<File>()
    private val released = mutableSetOf<File>()

    fun newId(): Long = ids.incrementAndGet()

    /** Starts a save for session [id]. The save keeps [EditSaveRequest.source] until it ends. */
    fun start(id: Long, request: EditSaveRequest) {
        synchronized(this) { inUse += request.source }
        saves.update { it + (id to EditSave.Running(id)) }
        scope.launch {
            val result = try {
                oneAtATime.withLock {
                    val uri = render(request) { progress -> saves.update { it + (id to EditSave.Running(id, progress)) } }
                    EditSave.Saved(id, uri, request.upload)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SmallerPhotoCopyRequired) {
                EditSave.NeedsSmallerCopy(id, e.width, e.height, request.upload)
            } catch (_: Exception) {
                EditSave.Failed(id)
            } finally {
                synchronized(this@PhotoEditSaves) {
                    inUse -= request.source
                    if (released.remove(request.source)) request.source.delete()
                }
            }
            saves.update { it + (id to result) }
        }
    }

    /** The session has seen the outcome of its save. */
    fun acknowledge(id: Long) = saves.update { it - id }

    /** The screen no longer needs [file]: delete it now, or when the save reading it ends. */
    fun release(file: File) = synchronized(this) {
        if (file in inUse) released += file else file.delete()
    }

    companion object {
        /** Lives as long as the app process, not the screen. */
        fun app(context: Context): PhotoEditSaves = shared ?: synchronized(this) {
            shared ?: PhotoEditSaves(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { request, progress ->
                val output = renderPhotoEdit(request.source, request.step.crop, request.step.turns, allowSmallerCopy = request.allowSmaller,
                    recipe = request.step.recipe, progress = progress)
                try { saveEditedCopy(context.applicationContext, request.media, output, source = request.source) } finally { output.recycle() }
            }.also { shared = it }
        }

        @Volatile private var shared: PhotoEditSaves? = null
    }
}
