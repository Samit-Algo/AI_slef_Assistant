package com.heybuddy.ui

import com.heybuddy.security.VaultLockState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** After ANY unlock the vault must open at the list; Back follows the owner's rules (Task 1.8b). */
class VaultNavigatorTest {

    private val lockState = VaultLockState()
    private val navigator = VaultNavigator(lockState)

    @Test
    fun startsAtTheList() {
        assertEquals(VaultScreen.List, navigator.screen.value)
    }

    @Test
    fun lockingFromAddDocumentGoesBackToTheList() {
        lockState.markUnlocked()
        navigator.open(VaultScreen.AddDocument)

        lockState.lock()

        assertEquals(VaultScreen.List, navigator.screen.value)
    }

    @Test
    fun lockingFromTheViewerGoesBackToTheList() {
        lockState.markUnlocked()
        navigator.open(VaultScreen.Viewer(DOCUMENT_ID))

        lockState.lock()

        assertEquals(VaultScreen.List, navigator.screen.value)
    }

    @Test
    fun unlockingAlwaysStartsAtTheList() {
        navigator.open(VaultScreen.Viewer(DOCUMENT_ID))

        lockState.markUnlocked()

        assertEquals(VaultScreen.List, navigator.screen.value)
    }

    @Test
    fun aFreshStartAfterThePhoneClosedTheAppIsAtTheList() {
        // The phone closing the app loses everything in memory; a new navigator is what comes back.
        navigator.open(VaultScreen.Viewer(DOCUMENT_ID))

        val afterRestart = VaultNavigator(VaultLockState())

        assertEquals(VaultScreen.List, afterRestart.screen.value)
    }

    @Test
    fun backFromTheViewerGoesToTheList() {
        navigator.open(VaultScreen.Viewer(DOCUMENT_ID))

        val handled = navigator.handleBack()

        assertTrue(handled)
        assertEquals(VaultScreen.List, navigator.screen.value)
    }

    @Test
    fun backFromAddDocumentGoesToTheList() {
        navigator.open(VaultScreen.AddDocument)

        val handled = navigator.handleBack()

        assertTrue(handled)
        assertEquals(VaultScreen.List, navigator.screen.value)
    }

    @Test
    fun backOnTheListIsLeftToTheSystemSoTheAppCloses() {
        val handled = navigator.handleBack()

        assertFalse(handled)
        assertEquals(VaultScreen.List, navigator.screen.value)
    }

    private companion object {
        const val DOCUMENT_ID = "11111111-1111-1111-1111-111111111111"
    }
}
