package com.heybuddy

import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks the INSTALLED app, as the phone sees it (rules 1 and 15; spec 7.3): it holds no network
 * permission at all, even with ML Kit inside, and only the launcher activity can be opened by other
 * apps. Reads the real package, so a library adding something back can never slip through.
 */
@RunWith(AndroidJUnit4::class)
class NoNetworkAndExportsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theInstalledAppHoldsNoNetworkPermission() {
        val requested = installedPackage().requestedPermissions?.toSet() ?: emptySet()

        for (permission in NETWORK_PERMISSIONS) {
            assertTrue("$permission must not be requested", permission !in requested)
        }
    }

    @Test
    fun onlyTheLauncherActivityIsExported() {
        val info = installedPackage()
        val components = mutableListOf<ComponentInfo>()
        info.activities?.let { activities -> components.addAll(activities) }
        info.services?.let { services -> components.addAll(services) }
        info.receivers?.let { receivers -> components.addAll(receivers) }
        info.providers?.let { providers -> components.addAll(providers) }

        val exported = components.filter { component -> component.exported }.map { component -> component.name }

        assertEquals(listOf(MainActivity::class.java.name), exported)
    }

    @Suppress("DEPRECATION") // The flag-object overload needs API 33; this app supports API 30+.
    private fun installedPackage(): PackageInfo {
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.MATCH_DISABLED_COMPONENTS
        return context.packageManager.getPackageInfo(context.packageName, flags)
    }

    private companion object {
        val NETWORK_PERMISSIONS = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_NETWORK_STATE",
            "android.permission.CHANGE_WIFI_STATE",
        )
    }
}
