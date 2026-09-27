package com.hisab.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.hisab.app.data.sync.SyncScheduler

/**
 * Starts background sync once, for the life of the process (Steps 62-65).
 *
 * Locale pinning deliberately stays in `MainActivity.onCreate`, not here:
 * AppCompat's language API silently does nothing this early, before any
 * Activity exists (see CURRENT_PHASE.md's Step 20 note, and the empty
 * `Application` class that mistake removed). Background sync has no such
 * dependency on an Activity, so it belongs here instead — the one place that
 * runs whenever the process starts, whether or not anyone opens the app.
 */
class HisabApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        SyncScheduler.schedulePeriodic(this)

        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val request =
            NetworkRequest
                .Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        connectivityManager.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    SyncScheduler.triggerNow(applicationContext)
                }
            },
        )
    }
}
