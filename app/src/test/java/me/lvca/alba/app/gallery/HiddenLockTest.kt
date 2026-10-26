package me.lvca.alba.app.gallery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.AlbaViewModel
import me.lvca.alba.app.platform.UnlockResult
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.testDependencies
import me.lvca.alba.engine.model.GalleryQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Hidden opens only after an unlock, and never comes back by itself. */
@RunWith(AndroidJUnit4::class)
class HiddenLockTest {
    @get:Rule val onboarding = OnboardingRule()
    private val app get() = ApplicationProvider.getApplicationContext<AlbaApplication>()

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) =
        AlbaViewModel(testDependencies(app.graph), saved).also { ViewModelStore().put("hidden", it) }

    @Test fun hiddenNeedsAnUnlockAndLocksAgainWhenAlbaLeavesTheScreen() {
        val vm = viewModel()
        vm.gallery.setQuery(GalleryQuery(collectionId = GalleryQuery.HIDDEN))
        assertNull(vm.gallery.currentQuery().collectionId)
        vm.hiddenUnlockAnswered(UnlockResult.CANCELED)
        assertNull(vm.gallery.currentQuery().collectionId)
        vm.hiddenUnlockAnswered(UnlockResult.UNLOCKED)
        assertEquals(GalleryQuery.HIDDEN, vm.gallery.currentQuery().collectionId)
        vm.gallery.lockHidden()
        assertNull(vm.gallery.currentQuery().collectionId)
        // Leaving Hidden for another view locks it too: coming back needs another unlock.
        vm.hiddenUnlockAnswered(UnlockResult.UNLOCKED)
        vm.gallery.setQuery(GalleryQuery(collectionId = GalleryQuery.FAVORITES))
        vm.gallery.setQuery(GalleryQuery(collectionId = GalleryQuery.HIDDEN))
        assertNull(vm.gallery.currentQuery().collectionId)
    }

    @Test fun aRestoredQueryNeverReopensHidden() {
        val vm = viewModel(SavedStateHandle(mapOf("collection" to GalleryQuery.HIDDEN)))
        assertNull(vm.gallery.currentQuery().collectionId)
    }
}
