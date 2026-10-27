package com.heybuddy.security

import androidx.fragment.app.FragmentActivity
import com.heybuddy.R

/**
 * The real [ReAuthPrompt]: the same fingerprint/PIN prompt as unlock, with "Confirm it's you" text.
 *
 * Create one per screen action; it holds the activity only while that action runs.
 *
 * @param activity The activity that shows the prompt.
 * @param unlocker Shows the system prompt.
 */
class BiometricReAuthPrompt(
    private val activity: FragmentActivity,
    private val unlocker: BiometricUnlocker,
) : ReAuthPrompt {

    override fun ask(onConfirmed: () -> Unit, onDeclined: () -> Unit) {
        val title = activity.getString(R.string.reauth_prompt_title)
        val subtitle = activity.getString(R.string.reauth_prompt_subtitle)
        unlocker.confirmIdentity(activity, title, subtitle, onConfirmed, onDeclined)
    }
}
