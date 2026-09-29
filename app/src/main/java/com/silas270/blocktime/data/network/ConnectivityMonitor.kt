package com.silas270.blocktime.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which networks are usable, keyed by network, and the one answer the app needs: is the device
 * connected? Pure, so the rule can be unit tested without Android's callbacks.
 *
 * **Connected means the default network is usable and so is at least one physical network**
 * (Wi-Fi, cellular, ethernet; never a VPN). Usable is `INTERNET` and `VALIDATED`. The default
 * network alone is not enough, because with a VPN it is the tunnel, and Android keeps a tunnel
 * marked validated after everything under it is gone: with ProtonVPN on and Wi-Fi and mobile data
 * off, the tunnel still read `INTERNET` and `VALIDATED` while nothing got through. The physical
 * networks alone are not enough either, because a VPN that is down with its kill switch on leaves
 * a working Wi-Fi the app cannot use; the default network is then lost.
 */
internal class NetworkUsability {
    private var defaultUsable = false
    private val physical = mutableMapOf<String, Boolean>()

    @Synchronized
    fun setDefault(usable: Boolean): Boolean {
        defaultUsable = usable
        return connected()
    }

    @Synchronized
    fun setPhysical(network: String, usable: Boolean): Boolean {
        physical[network] = usable
        return connected()
    }

    @Synchronized
    fun lostPhysical(network: String): Boolean {
        physical.remove(network)
        return connected()
    }

    @Synchronized
    fun connected(): Boolean = defaultUsable && physical.values.any { it }
}

/**
 * Tracks whether the device can actually reach the internet, by the rule of [NetworkUsability].
 *
 * A captive portal or a Wi-Fi network with no uplink counts as offline, because tile downloads
 * would just hang until they timed out, and so does a VPN tunnel with nothing under it.
 *
 * Seeded synchronously in the constructor so the first frame already shows the right state,
 * then updated by two callbacks: one for the default network, one for every physical network
 * with internet. It is registered on the application context and lives for the whole process
 * (see [OfflineModeController.getInstance]).
 */
class ConnectivityMonitor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val usability = NetworkUsability()

    private val _isConnected = MutableStateFlow(seed())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _isConnected.value = usability.setDefault(capabilities.isUsable())
        }

        override fun onLost(network: Network) {
            _isConnected.value = usability.setDefault(false)
        }
    }

    private val physicalCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _isConnected.value = usability.setPhysical(network.toString(), capabilities.isUsable())
        }

        override fun onLost(network: Network) {
            _isConnected.value = usability.lostPhysical(network.toString())
        }
    }

    init {
        try {
            connectivityManager?.registerDefaultNetworkCallback(defaultCallback)
            // A request without a transport matches every network with the capability; the
            // builder's default NOT_VPN capability leaves the tunnels out.
            val physical = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivityManager?.registerNetworkCallback(physical, physicalCallback)
        } catch (e: RuntimeException) {
            // Only thrown if the per-app callback limit is hit; the seeded value stays in place.
            Log.e(TAG, "Could not register network callback", e)
        }
    }

    /** The state right now, read synchronously; the callbacks then keep it current. */
    @Suppress("DEPRECATION") // allNetworks: the only synchronous listing; the callbacks take over at once.
    private fun seed(): Boolean {
        val cm = connectivityManager ?: return false
        usability.setDefault(cm.getNetworkCapabilities(cm.activeNetwork)?.isUsable() == true)
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                usability.setPhysical(network.toString(), caps.isUsable())
            }
        }
        return usability.connected()
    }

    private fun NetworkCapabilities.isUsable(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private companion object {
        const val TAG = "ConnectivityMonitor"
    }
}
