package com.heybuddy

import android.app.Application
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.heybuddy.util.LogEvent
import com.heybuddy.util.SafeLog

/**
 * Application class. Creates the [AppContainer] once, when the app process starts.
 */
class HeyBuddyApp : Application() {

    /** Shared objects for the whole app. Ready before any activity starts. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Tink must know the streaming AEAD key type before any document key is created or read.
        StreamingAeadConfig.register()
        container = AppContainer(appContext = this)
        SafeLog.info(LogEvent.APP_STARTED)
    }
}
