package com.noteflowai.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

/**
 * Thin wrapper over [ConnectivityManager] for offline-aware pipeline gating.
 *
 * [hasNetwork] reports whether the device currently has a network with
 * internet capability. It is fail-open: when the connectivity service is
 * unavailable the result is `true`, so legitimate work is never skipped
 * because of a broken system service.
 *
 * [startWatching] registers a one-shot network callback that invokes
 * [onAvailable] whenever a network becomes available. Used by the ingestion
 * pipeline to resume deferred stages when connectivity returns. The callback
 * is registered at most once per instance.
 */
class ConnectivityChecker(private val context: Context) {

    @Volatile
    private var watching = false

    /** True if the device has an active network with internet capability. */
    fun hasNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true // fail-open: cannot determine, assume online
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return true
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Invoke [onAvailable] whenever a network becomes available. Safe to call
     * multiple times — the callback is installed only on the first call.
     */
    fun startWatching(onAvailable: () -> Unit) {
        if (watching) return
        synchronized(this) {
            if (watching) return
            watching = true
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onAvailable()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(callback)
        } else {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
        }
    }
}
