package io.nekohasekai.sagernet.simple.regime

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs

/**
 * The physical default network for NetworkRegimeWatcher, from a ConnectivityManager callback of its own on a thread of
 * its own (design.md D6). The same request DefaultNetworkListener makes (INTERNET, NOT_RESTRICTED and, by default,
 * NOT_VPN: never the VPN itself), but its callbacks do not wait behind DefaultNetworkListener's, which run one listener
 * after another on one thread — and a listener per closed core box piles up there (upstream: the monitors of
 * NativeInterface are never removed), so a network change can reach the end of that queue tens of seconds late.
 *
 * [onChange] gets the new network, or null when it was lost; on this monitor's thread.
 */
internal class PhysicalNetworkMonitor(private val onChange: (Network?) -> Unit) {

    private val thread = HandlerThread("simple-regime-network").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile
    private var current: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (current == network) return
            current = network
            onChange(network)
        }

        override fun onLost(network: Network) {
            if (current != network) return
            current = null
            onChange(null)
        }
    }

    /** Registers the callback; false when the system refused it (the caller then uses DefaultNetworkListener). */
    fun start(): Boolean = try {
        register()
        true
    } catch (e: Exception) {
        Logs.w("simple regime: no network callback of its own", e)
        thread.quitSafely()
        false
    }

    fun stop() {
        runCatching { SagerNet.connectivity.unregisterNetworkCallback(callback) }.onFailure { Logs.w(it) }
        thread.quitSafely()
    }

    /** As DefaultNetworkListener registers (see there why a REQUEST rather than the default network callback). */
    private fun register() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        val connectivity = SagerNet.connectivity
        when {
            Build.VERSION.SDK_INT >= 31 -> connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
            Build.VERSION.SDK_INT >= 28 -> connectivity.requestNetwork(request, callback, handler)
            Build.VERSION.SDK_INT >= 26 -> connectivity.registerDefaultNetworkCallback(callback, handler)
            else -> connectivity.registerDefaultNetworkCallback(callback)
        }
    }
}
