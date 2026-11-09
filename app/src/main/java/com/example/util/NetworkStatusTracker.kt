package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NetworkConnectionState(
    val displayLabel: String,
    val shortLabel: String,
    val isConnected: Boolean
) {
    ONLINE_WIFI("Online (Wi-Fi)", "Wi-Fi", true),
    ONLINE_CELLULAR("Online (Cellular)", "Cellular", true),
    ONLINE_ETHERNET("Online (Ethernet)", "Ethernet", true),
    OFFLINE("Offline (No Connection)", "Offline", false)
}

/**
 * Monitors real-time device network connectivity via ConnectivityManager
 * and exposes a reactive StateFlow<NetworkConnectionState> for the bottom bar indicator.
 */
class NetworkStatusTracker(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _networkState = MutableStateFlow(resolveCurrentState(appContext))
    val networkState: StateFlow<NetworkConnectionState> = _networkState.asStateFlow()

    private var isRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _networkState.value = resolveCurrentState(appContext)
        }

        override fun onLost(network: Network) {
            _networkState.value = resolveCurrentState(appContext)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            _networkState.value = mapCapabilities(networkCapabilities)
        }
    }

    init {
        startTracking()
    }

    fun startTracking() {
        if (isRegistered || connectivityManager == null) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            isRegistered = true
            _networkState.value = resolveCurrentState(appContext)
        } catch (_: Exception) {
            _networkState.value = resolveCurrentState(appContext)
        }
    }

    fun refresh() {
        _networkState.value = resolveCurrentState(appContext)
    }

    fun stopTracking() {
        if (!isRegistered || connectivityManager == null) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        } finally {
            isRegistered = false
        }
    }

    companion object {
        fun resolveCurrentState(context: Context): NetworkConnectionState {
            return try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return NetworkConnectionState.OFFLINE
                val activeNetwork = cm.activeNetwork ?: return NetworkConnectionState.OFFLINE
                val caps = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkConnectionState.OFFLINE
                mapCapabilities(caps)
            } catch (_: Exception) {
                NetworkConnectionState.OFFLINE
            }
        }

        fun mapCapabilities(caps: NetworkCapabilities?): NetworkConnectionState {
            if (caps == null) return NetworkConnectionState.OFFLINE
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (!hasInternet) return NetworkConnectionState.OFFLINE

            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkConnectionState.ONLINE_WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkConnectionState.ONLINE_CELLULAR
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkConnectionState.ONLINE_ETHERNET
                else -> NetworkConnectionState.ONLINE_WIFI
            }
        }
    }
}
