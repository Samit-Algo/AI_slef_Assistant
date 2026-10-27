package com.heybuddy

import android.content.Context

/**
 * Creates and holds the app's shared objects (manual dependency injection, spec section 2).
 *
 * Empty for now. Later tasks add the master key manager, crypto helpers and the vault
 * repository here, so every screen gets the same instances.
 *
 * @param appContext The application context, safe to keep for the whole app's life.
 */
class AppContainer(private val appContext: Context)
