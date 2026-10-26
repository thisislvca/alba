package me.lvca.alba.app.messages

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/** Resolve transient UI messages at display time, including after a language change. */
sealed interface UiMessage {
    fun resolve(context: Context): String

    data class Text(@param:StringRes val resource: Int, val arguments: List<Any>) : UiMessage {
        override fun resolve(context: Context) = context.getString(resource, *arguments.toTypedArray())
    }
    data class Quantity(@param:PluralsRes val resource: Int, val count: Int, val arguments: List<Any>) : UiMessage {
        override fun resolve(context: Context) = context.resources.getQuantityString(resource, count, *arguments.toTypedArray())
    }

    /** A message that offers to reverse what it reports, such as a move to the trash. */
    data class Undoable(val message: UiMessage, val undo: UndoAction) : UiMessage {
        override fun resolve(context: Context) = message.resolve(context)
    }
    companion object {
        operator fun invoke(@StringRes resource: Int, arguments: List<Any> = emptyList()): UiMessage = Text(resource, arguments)
        fun quantity(@PluralsRes resource: Int, count: Int): UiMessage = Quantity(resource, count, listOf(count))
    }
}

/** What an [UiMessage.Undoable] message reverses. Only moves that can really be taken back have one. */
sealed interface UndoAction {
    /** Items moved to iCloud Recently Deleted: put them back in the library. */
    data class CloudTrash(val mediaIds: List<String>) : UndoAction

    /** Phone items moved to Android's trash: ask Android to restore these content URIs. */
    data class PhoneTrash(val contentUris: List<String>) : UndoAction

    /** A selection moved to both trashes at once: put the iCloud items back and ask Android to restore the rest. */
    data class MixedTrash(val cloud: CloudTrash, val phone: PhoneTrash) : UndoAction
}
