package com.heybuddy

import android.content.Context
import com.heybuddy.security.MasterKeyManager

/**
 * Creates and holds the app's shared objects (manual dependency injection, spec section 2).
 *
 * Later tasks add crypto helpers and the vault repository here, so every screen gets the
 * same instances.
 *
 * @param appContext The application context, safe to keep for the whole app's life.
 */
class AppContainer(private val appContext: Context) {

    /** The one manager for the vault's Keystore master key. */
    val masterKeyManager: MasterKeyManager = MasterKeyManager()
}
