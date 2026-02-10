package com.ooustream.iptv.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class NetworkState(
    val isConnected: Boolean = false,
    val isWifi: Boolean = false,
    val estimatedBandwidthKbps: Int = 0
)

@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _state = MutableStateFlow(getCurrentState())
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateState()
        }

        override fun onLost(network: Network) {
            _state.value = NetworkState(isConnected = false)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val bandwidth = capabilities.linkDownstreamBandwidthKbps
            _state.value = NetworkState(
                isConnected = true,
                isWifi = isWifi,
                estimatedBandwidthKbps = bandwidth
            )
        }
    }

    init {
        registerCallback()
    }

    private fun registerCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } else {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        }
    }

    private fun updateState() {
        _state.value = getCurrentState()
    }

    @Suppress("DEPRECATION")
    private fun getCurrentState(): NetworkState {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return NetworkState()
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return NetworkState()
            return NetworkState(
                isConnected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                estimatedBandwidthKbps = caps.linkDownstreamBandwidthKbps
            )
        } else {
            val info = connectivityManager.activeNetworkInfo
            return NetworkState(
                isConnected = info?.isConnected == true,
                isWifi = info?.type == ConnectivityManager.TYPE_WIFI,
                estimatedBandwidthKbps = 0
            )
        }
    }
}
