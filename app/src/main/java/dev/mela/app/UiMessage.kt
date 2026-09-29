package dev.mela.app

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
    companion object {
        operator fun invoke(@StringRes resource: Int, arguments: List<Any> = emptyList()): UiMessage = Text(resource, arguments)
        fun quantity(@PluralsRes resource: Int, count: Int): UiMessage = Quantity(resource, count, listOf(count))
    }
}
