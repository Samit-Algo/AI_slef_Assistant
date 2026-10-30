package com.heybuddy.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/** The CAMERA permission states (Task 2.4), including Android's hidden "Don't ask again". */
class CameraAccessRulesTest {

    @Test
    fun grantedOpensTheCamera() {
        assertEquals(CameraAccess.GRANTED, CameraAccessRules.beforeOpening(granted = true))
    }

    @Test
    fun whenMissingOurExplanationAlwaysComesFirst() {
        // Even after "Don't ask again" or a switch-off in Settings: only Android's answer tells them apart.
        assertEquals(CameraAccess.ASK_WITH_EXPLANATION, CameraAccessRules.beforeOpening(granted = false))
    }

    @Test
    fun answersMapToTheRightScreen() {
        assertEquals(CameraAccess.GRANTED, CameraAccessRules.afterAnswer(granted = true, showRationale = false))
        assertEquals(CameraAccess.DENIED, CameraAccessRules.afterAnswer(granted = false, showRationale = true))
        assertEquals(CameraAccess.DENIED_FOR_GOOD, CameraAccessRules.afterAnswer(granted = false, showRationale = false))
    }
}
