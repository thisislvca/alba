package me.lvca.alba.app.messages

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.R
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.source.UnsupportedUploadException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoredMessageTest {
    private fun localized(tag: String): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) })
    }

    @Test fun oldAndNewJournalMessagesUseTheSameLocalizedResource() {
        StoredMessage.entries.forEach { message ->
            assertEquals(localizedStoredMessage(message.legacyText), localizedStoredMessage(message.code))
        }
        assertEquals(UiMessage(R.string.operation_needs_attention), localizedStoredMessage("unknown"))
    }

    @Test fun journalMessagesReadInTheAppLanguageAndUnsupportedLanguagesFallBackToEnglish() {
        val english = localized("en")
        val italian = localized("it")
        val needsAttention = italian.getString(R.string.operation_needs_attention)
        // Messages journalled by older versions, under the old app name, still have their own translation.
        listOf("Mela currently uploads JPEG originals only.",
            "Upload result was lost. Mela will check iCloud without uploading again.").forEach { legacy ->
            assertNotEquals(legacy, needsAttention, localizedStoredMessage(legacy).resolve(italian))
        }
        listOf(UnsupportedUploadException.MESSAGE, UnsupportedUploadException.ANIMATED, UnsupportedUploadException.VIDEO_CODEC,
            UnsupportedUploadException.INVALID, "iCloud is processing this file. The original is still on this phone.",
            "iCloud could not process this file. The original is still on this phone. This upload will not be repeated.").forEach { message ->
            assertNotEquals(message, needsAttention, localizedStoredMessage(message).resolve(italian))
            assertEquals(StoredMessage.decode(message)?.legacyText ?: message, localizedStoredMessage(message).resolve(english))
        }
        assertEquals(english.getString(R.string.library), localized("fr").getString(R.string.library))
        assertNotEquals(english.getString(R.string.library), italian.getString(R.string.library))
    }
}
