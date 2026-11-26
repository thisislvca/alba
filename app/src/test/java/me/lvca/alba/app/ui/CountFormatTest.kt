package me.lvca.alba.app.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Counts are grouped the way the reader's language groups them, whether formatted in code or by a string resource. */
@RunWith(AndroidJUnit4::class)
class CountFormatTest {
    private fun localized(locale: Locale): Context = ApplicationProvider.getApplicationContext<Context>()
        .createConfigurationContext(Configuration().apply { setLocale(locale) })

    @Test fun countsAreGroupedForTheLocale() {
        assertEquals("12,607", formatCount(12_607, Locale.US))
        assertEquals("12.607", formatCount(12_607, Locale.ITALY))
        assertEquals("3,805", formatCount(3_805, Locale.US))
        assertEquals("3.805", formatCount(3_805, Locale.ITALY))
        assertEquals("1,234,567", formatCount(1_234_567L, Locale.US))
        assertEquals("999", formatCount(999, Locale.ITALY))
        assertEquals("0", formatCount(0, Locale.US))
    }

    @Test fun stringResourcesGroupCountsLikeTheHelper() {
        for (locale in listOf(Locale.US, Locale.ITALY)) {
            val resources = localized(locale).resources
            val expected = formatCount(1_657, locale)
            assertEquals("$expected ${if (locale == Locale.US) "items" else "elementi"}",
                resources.getQuantityString(R.plurals.item_count, 1_657, 1_657))
            assertEquals(true, resources.getQuantityString(R.plurals.cloud_photo_count, 12_737, 12_737).startsWith(formatCount(12_737, locale)))
            assertEquals(true, resources.getString(R.string.phone_photo_count, 3_805).startsWith(formatCount(3_805, locale)))
        }
    }

    @Test fun dimensionsStayUngrouped() {
        // Apple writes pixel dimensions as plain numbers: 4032 × 3024.
        assertEquals("4032 × 3024", localized(Locale.US).resources.getString(R.string.pixel_dimensions, 4_032, 3_024))
    }
}
