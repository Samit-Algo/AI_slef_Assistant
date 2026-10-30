package com.heybuddy.ui.camera

/** Where the user stands with the CAMERA permission (Task 2.4). */
enum class CameraAccess {
    /** Allowed: the camera can open. */
    GRANTED,

    /** Not allowed yet, but Android may still ask: show our short explanation first. */
    ASK_WITH_EXPLANATION,

    /** Just said no: a calm message, with "Choose a photo" instead. */
    DENIED,

    /** Said no for good ("Don't ask again"): Android will not ask again, so offer the phone's Settings. */
    DENIED_FOR_GOOD,
}

/**
 * Pure rules for [CameraAccess]. Android does not say directly that the user chose "Don't ask
 * again", and BEFORE asking it cannot be told apart from "turned off in Settings" (where Android
 * would ask again). So it is decided only from Android's real answer: denied, and Android no
 * longer wants to explain why, means it did not even show its question.
 */
object CameraAccessRules {

    /**
     * Before the camera opens: our short explanation comes first whenever the permission is missing.
     *
     * @param granted Whether the permission is given now.
     * @return What to show.
     */
    fun beforeOpening(granted: Boolean): CameraAccess {
        return if (granted) CameraAccess.GRANTED else CameraAccess.ASK_WITH_EXPLANATION
    }

    /**
     * After the user answered Android's question.
     *
     * @param granted The answer.
     * @param showRationale Android's shouldShowRequestPermissionRationale after the answer.
     * @return What to show.
     */
    fun afterAnswer(granted: Boolean, showRationale: Boolean): CameraAccess {
        return when {
            granted -> CameraAccess.GRANTED
            showRationale -> CameraAccess.DENIED
            else -> CameraAccess.DENIED_FOR_GOOD
        }
    }
}
