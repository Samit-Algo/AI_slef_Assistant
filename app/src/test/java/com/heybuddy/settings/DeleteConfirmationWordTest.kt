package com.heybuddy.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Delete everything" only works after typing DELETE exactly. */
class DeleteConfirmationWordTest {

    @Test
    fun exactlyDeleteInCapitalsIsAccepted() {
        assertTrue(isDeleteEverythingConfirmed("DELETE"))
    }

    @Test
    fun anythingElseIsRefused() {
        val notExact = listOf("", "delete", "Delete", " DELETE", "DELETE ", "DELET", "DELETE!", "DELETED", "ＤＥＬＥＴＥ")
        for (typed in notExact) {
            assertFalse("\"$typed\" must not count", isDeleteEverythingConfirmed(typed))
        }
    }

    @Test
    fun theButtonStaysOffWhileRunningEvenWithTheRightWord() {
        val running = com.heybuddy.ui.settings.DeleteAllDialogState(typed = "DELETE", running = true)
        val ready = com.heybuddy.ui.settings.DeleteAllDialogState(typed = "DELETE")

        assertFalse(running.canConfirm)
        assertTrue(ready.canConfirm)
    }
}
