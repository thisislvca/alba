package me.lvca.alba.app.account

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.ui.designsystem.AccountIdentity
import me.lvca.alba.app.ui.designsystem.LocalAccountIdentity
import me.lvca.alba.app.ui.designsystem.MonogramGradients
import me.lvca.alba.app.ui.designsystem.monogram
import me.lvca.alba.app.ui.designsystem.monogramGradient
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.gallery.AccountButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** The account's avatar in the header, and Apple's monograms for anyone without a photo. */
@RunWith(AndroidJUnit4::class)
class AccountAvatarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theHeaderShowsTheAccountsPhoto() {
        val photo = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(android.graphics.Color.rgb(83, 132, 161))
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
        }
        compose.setContent { AlbaTheme(darkTheme = false) {
            CompositionLocalProvider(LocalAccountIdentity provides AccountIdentity(photo, "Alex Example")) {
                AccountButton("alex@example.com", null, "Account", {}, Modifier.testTag("account-button"))
            }
        } }
        compose.waitUntil(SHORT_WAIT) { compose.onAllNodesWithTag("account-photo", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("account-button", useUnmergedTree = true).assert(hasAnyDescendant(hasTestTag("account-photo")))
    }

    @Test fun withoutAPhotoTheHeaderShowsTheAccountsMonogram() {
        compose.setContent { AlbaTheme(darkTheme = false) {
            CompositionLocalProvider(LocalAccountIdentity provides AccountIdentity(null, "Alex Example")) {
                AccountButton("alex@example.com", null, "Account", {}, Modifier.testTag("account-button"))
            }
        } }
        compose.onNodeWithTag("account-button", useUnmergedTree = true).assert(hasAnyDescendant(hasText("AE")))
        compose.onNodeWithTag("account-photo", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun monogramsTakeTheGivenAndFamilyNamesInitials() {
        assertEquals("GF", monogram("Giulia Ferri"))
        assertEquals("CM", monogram("  carlo   de  Marchi "))
        assertEquals("ÉZ", monogram("Émile Zola"))
        // A decomposed accent stays on its letter.
        assertEquals("ÑS", monogram("Ñuria Sol"))
        assertEquals("ÅL", monogram("åsa Lindqvist"))
        assertEquals("M", monogram("Madonna"))
        assertEquals("A", monogram("alex.example@icloud.com"))
        assertEquals("L", monogram("(Luca)"))
        assertNull(monogram(null))
        assertNull(monogram("   "))
        assertNull(monogram("@example.com"))
    }

    @Test fun aNameAlwaysGetsTheSameGradient() {
        assertEquals("No name is Apple's grey", MonogramGradients.first(), monogramGradient(null))
        assertEquals(MonogramGradients.first(), monogramGradient("  "))
        assertEquals(monogramGradient("Giulia Ferri"), monogramGradient("Giulia Ferri"))
        assertEquals(monogramGradient("Giulia Ferri"), monogramGradient(" giulia ferri "))
        val names = listOf("Giulia Ferri", "Carlo Marchi", "Alex Example", "Sample Owner", "Luca", "Émile Zola", "Ana", "Bo")
        assertTrue("Different people get different colours", names.map(::monogramGradient).toSet().size > 1)
        names.forEach { assertTrue(monogramGradient(it) in MonogramGradients) }
    }
}
